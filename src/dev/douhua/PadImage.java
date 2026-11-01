package dev.douhua;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 触控板背景图（设置 → 外观）：导入、后台解码、画。怎么摆（显示方式、焦点、解多大）在纯 Java 的 PadFit。
 *
 * - 导入（设置页，静态方法）：选完图在后台线程把原文件拷进 files/padbg/（最多 50MB），用 ImageDecoder 解一张小图确认能解（顺带当设置页的预览），
 *   能解才换名成 <时间>.img、写 Prefs、删掉旧的；解不了原来的图不受影响。不记 URI（读权限是临时的，原图被删、被挪都不影响）。
 *   完了在主线程通知设置页（没挂着就留下结果码，下次 start 取走）和主界面（成功时，在前台的话马上换图）。
 *   日志里不写 URI 和文件名（URI 里可能带原图的显示名），只写错误类型。
 * - 显示（MainActivity 一个实例，两块触控板共用同一张位图）：后台单线程解码（心跳在主线程，主线程卡住超过 3 秒服务会释放触摸屏，
 *   大图解一次几百毫秒），解完 post 回主线程，用代数号丢掉过时的结果。解码多大按窗口最大可能的长边（PadFit.decodeSize），软件位图 + mipmap。
 * - 画：每块触控板一个 {@link Brush}（自己的 Paint / BitmapShader / 变换），{@link #drawImage} 设变换、按不透明度画一个圆角矩形；
 *   平铺用 REPEAT，其它用 DECAL（图外透明，完整显示 / 原大小空出来的地方露触控板底色）。动图只解第一帧（decodeBitmap 本来就是）。
 * 触摸完全不经过这里：区域上报、分流、服务都不知道有背景图。
 */
final class PadImage {
    private static final String TAG = "douhua";
    /** files/ 下放背景图的目录：里面只有当前那张（导入中多一个 .incoming）。 */
    static final String DIR = "padbg";
    /** 导入的结果；NO_RESULT = 没有留着没人取的结果（takeMissedResult）。 */
    static final int OK = 0, ERR_TOO_BIG = 1, ERR_DECODE = 2, NO_RESULT = -1;
    /** 设置页预览、导入时验证用的小图：长边最多这么多像素（预览格子、焦点那张缩略图都比它小）。 */
    static final int PREVIEW_SIDE = 1024;

    /** 导入、删文件、设置页预览的解码：一个后台线程排队，同一时刻只有一件事碰 files/padbg/（重置时删文件排在进行中的导入后面）。 */
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "douhua-padbg-io");
        t.setDaemon(true);
        return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // ═══════════ 导入（设置页） ═══════════

    /** 导入完（主线程）：result 是 OK / ERR_*；成功时 preview 是一张小图（长边 ≤ PREVIEW_SIDE），srcW / srcH 是原图（转正后）的大小。 */
    interface ImportListener {
        void onImported(int result, String file, Bitmap preview, int srcW, int srcH);
    }

    /** 只在主线程读写。 */
    private static boolean importing;
    private static ImportListener importListener;
    /** 导入完时设置页没挂着（读取中离开了设置页、转屏重建的空当）：结果码留在这里，设置页下次 start 时取走显示（太大 / 读不了的提示不丢）。 */
    private static int missedResult = NO_RESULT;
    /**
     * 主界面（MainActivity onStart 到 onStop 之间挂着）：导入成功、Prefs 换成新文件后叫它照新的 Prefs 重新 apply。
     * 读取中按返回回到主界面时，onStart 的 applyPrefs 读到的还是旧图，没有这个要等下一次 onStart 才换；不在前台的下次 onStart 本来就读新的。
     */
    private static Runnable importedHook;

    /** 正在导入吗（设置页转屏重建后照样显示"正在读取"、按钮不让点）。 */
    static boolean importing() {
        return importing;
    }

    /** 设置页 onStart 时挂上（接着 takeMissedResult）；导入完时没人挂着就只写 Prefs、留下结果码（下次打开设置页自己解预览）。 */
    static void setImportListener(ImportListener l) {
        importListener = l;
    }

    /** 设置页 onStop 时摘掉：只摘自己的（转屏重建时新的那个可能已经先挂上了）。 */
    static void removeImportListener(ImportListener l) {
        if (importListener == l) importListener = null;
    }

    /** 取走"没人收到的那次导入结果"（取了就清）；没有返回 NO_RESULT。 */
    static int takeMissedResult() {
        int r = missedResult;
        missedResult = NO_RESULT;
        return r;
    }

    /** 主界面 onStart 挂上、onStop 摘掉（只摘自己的）。 */
    static void setImportedHook(Runnable r) {
        importedHook = r;
    }

    static void removeImportedHook(Runnable r) {
        if (importedHook == r) importedHook = null;
    }

    static File dir(Context c) {
        return new File(c.getFilesDir(), DIR);
    }

    /**
     * 选完图（主线程调）：后台拷贝、验证、换名、写 Prefs、删旧的，完了在主线程通知。
     * 写 Prefs 也在后台做（SharedPreferences 线程安全）：设置页中途转屏重建、关掉，结果照样存下。
     */
    static void startImport(Context context, Uri uri) {
        Context app = context.getApplicationContext();
        importing = true;
        missedResult = NO_RESULT;   // 上一次没人取的结果作废：这次的会顶上
        IO.execute(() -> {
            Result r = importNow(app, uri);
            MAIN.post(() -> {
                importing = false;
                if (r.code == OK && importedHook != null) importedHook.run();   // 失败时 Prefs 没变，主界面不用动
                if (importListener != null) importListener.onImported(r.code, r.file, r.preview, r.srcW, r.srcH);
                else missedResult = r.code;   // 小图不留（最大约 4MB），设置页回来时按 Prefs 的文件名自己解
            });
        });
    }

    private static final class Result {
        int code = ERR_DECODE;
        String file;
        Bitmap preview;
        int srcW, srcH;
    }

    private static Result importNow(Context app, Uri uri) {
        Result r = new Result();
        File dir = dir(app);
        File incoming = new File(dir, ".incoming");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("建不了目录");
            if (!copyLimited(app, uri, incoming)) {
                r.code = ERR_TOO_BIG;
                Log.w(TAG, "背景图导入：超过 " + PadFit.MAX_FILE_MB + "MB，没要");
                return r;
            }
            boolean[] animated = new boolean[1];
            int[] size = new int[2];
            r.preview = decodePreview(incoming, size, animated);
            r.srcW = size[0];
            r.srcH = size[1];
            String name = System.currentTimeMillis() + ".img";   // 扩展名无所谓，ImageDecoder 按内容认格式；每次换名，MainActivity 比名字就知道要不要重新解
            File target = new File(dir, name);
            if (!incoming.renameTo(target)) throw new IOException("换名失败");
            new Prefs(app).setPadBgFile(name, animated[0]);
            deleteExcept(dir, name);
            r.code = OK;
            r.file = name;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "背景图导入失败：" + e.getClass().getSimpleName());
            r.code = ERR_DECODE;
            r.preview = null;
        } finally {
            if (incoming.exists() && !incoming.delete()) Log.w(TAG, "背景图导入：临时文件删不掉");
        }
        return r;
    }

    /** 拷到 out，超过 MAX_FILE_MB 就停、删掉，返回 false。云端照片可能要现下载，所以只在后台线程调。 */
    private static boolean copyLimited(Context app, Uri uri, File out) throws IOException {
        long limit = (long) PadFit.MAX_FILE_MB * 1024 * 1024;
        long total = 0;
        try (InputStream in = app.getContentResolver().openInputStream(uri); OutputStream os = new FileOutputStream(out)) {
            if (in == null) throw new IOException("打不开");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > limit) break;
                os.write(buf, 0, n);
            }
        }
        if (total > limit) {
            out.delete();
            return false;
        }
        return true;
    }

    /** 解一张长边 ≤ PREVIEW_SIDE 的小图（软件位图）；size 收原图（转正后）大小，animated 收是不是动图。解不了抛 IOException。 */
    private static Bitmap decodePreview(File f, int[] size, boolean[] animated) throws IOException {
        return ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (decoder, info, src) -> {
            Size s = info.getSize();
            size[0] = s.getWidth();
            size[1] = s.getHeight();
            if (animated != null) animated[0] = info.isAnimated();
            double k = Math.min(1, (double) PREVIEW_SIDE / Math.max(1, Math.max(s.getWidth(), s.getHeight())));
            decoder.setTargetSize(Math.max(1, (int) Math.round(s.getWidth() * k)), Math.max(1, (int) Math.round(s.getHeight() * k)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    /** 设置页的预览（打开设置页、转屏重建时）：后台解小图，完了在主线程回调；文件不在 / 解不了给 null。 */
    interface PreviewListener {
        void onPreview(String file, Bitmap preview, int srcW, int srcH);
    }

    static void loadPreview(Context context, String file, PreviewListener l) {
        File f = new File(dir(context), file);
        IO.execute(() -> {
            Bitmap b = null;
            int[] size = new int[2];
            try {
                if (f.isFile()) b = decodePreview(f, size, null);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "背景图预览解不了：" + e.getClass().getSimpleName());
            }
            Bitmap shown = b;
            MAIN.post(() -> l.onPreview(file, shown, size[0], size[1]));
        });
    }

    /** "移除图片" / "恢复默认设置"：删掉 files/padbg/ 下所有文件（后台，排在进行中的导入后面）。Prefs 由调用方清。 */
    static void deleteAll(Context context) {
        File dir = dir(context.getApplicationContext());
        IO.execute(() -> deleteExcept(dir, null));
    }

    private static void deleteExcept(File dir, String keep) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (keep != null && f.getName().equals(keep)) continue;
            if (!f.delete()) Log.w(TAG, "背景图：旧文件删不掉");
        }
    }

    // ═══════════ 显示（MainActivity） ═══════════

    /** 位图来了 / 没了 / 设置变了（主线程）：重画两块触控板，"有没有图"变了要给角上的小按钮换底。 */
    interface Listener {
        void onPadImageChanged();
    }

    private final Context app;
    private final Prefs prefs;
    private final Listener listener;
    /** 显示用的解码：自己一个后台线程（不和导入排一个队：导入可能在等云端下载）。 */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "douhua-padbg");
        t.setDaemon(true);
        return t;
    });
    /** 代数：每排一次解码、每放掉一次 +1；解完对不上就扔（连着换两次图、解到一半又改了设置）。后台线程开工前也看一眼，过时的不解。 */
    private final AtomicInteger gen = new AtomicInteger();
    private PadFit.Settings cfg = new PadFit.Settings("", false, PadFit.ALPHA_DEFAULT, false, null, null);
    private int maxSide = 1;
    private boolean destroyed;
    /** 手上的位图（null = 没有）和它是哪个文件、原图多大（转正后）。 */
    private Bitmap bitmap;
    private String bitmapFile;
    private int srcW, srcH;
    /** 正在解的（PadFit.Settings.decodeKey）：设置没变就不再排一次。 */
    private String pendingKey;

    PadImage(Context context, Prefs prefs, Listener listener) {
        this.app = context.getApplicationContext();
        this.prefs = prefs;
        this.listener = listener;
    }

    /** 有图可画吗（解出来了）。 */
    boolean shown() {
        return bitmap != null;
    }

    /**
     * 设置变了 / 回到前台（applyPrefs）：maxSide = 窗口最大可能的长边。文件换了、或者要的比手上的大 5% 以上就后台重新解，
     * 解完之前照旧画手上那张（不闪）；只是不透明度、焦点这类变了就直接重画。
     */
    void apply(PadFit.Settings s, int maxSide) {
        if (destroyed) return;
        cfg = s;
        this.maxSide = Math.max(1, maxSide);
        if (!s.hasImage()) {
            gen.incrementAndGet();
            pendingKey = null;
            bitmap = null;
            bitmapFile = null;
            listener.onPadImageChanged();
            return;
        }
        boolean enough = false;
        if (bitmap != null && s.file.equals(bitmapFile)) {
            int[] want = PadFit.decodeSize(srcW, srcH, this.maxSide, s.used());
            enough = !PadFit.needsRedecode(bitmap.getWidth(), bitmap.getHeight(), want[0], want[1]);
        } else if (bitmap != null) {
            bitmap = null;   // 换了张图：旧的不再画（新图解出来之前先只有底色，免得旧图配新设置）
            bitmapFile = null;
        }
        if (enough) {
            gen.incrementAndGet();   // 正在解的那次（更大的）用不着了
            pendingKey = null;
        } else {
            String key = s.decodeKey(this.maxSide);
            if (!key.equals(pendingKey)) decode(s, key);
        }
        listener.onPadImageChanged();
    }

    /** 窗口能有多大变了（Fold 展开、挪到外接屏）：按现在的设置再看一遍要不要重新解。 */
    void setMaxSide(int side) {
        if (side != maxSide) apply(cfg, side);
    }

    /** 放掉位图（进程退到后台、内存紧时；下次 onStart 的 apply 会重新解）。 */
    void release() {
        gen.incrementAndGet();
        pendingKey = null;
        if (bitmap == null) return;
        bitmap = null;
        bitmapFile = null;
        listener.onPadImageChanged();
    }

    void destroy() {
        release();
        destroyed = true;
        worker.shutdownNow();
    }

    private void decode(PadFit.Settings s, String key) {
        int g = gen.incrementAndGet();
        pendingKey = key;
        String name = s.file;
        File f = new File(dir(app), name);
        int side = maxSide;
        PadFit.Look[] looks = s.used();
        worker.execute(() -> {
            if (gen.get() != g) return;   // 排队期间又变了：这次不用解
            Bitmap b = null;
            int[] size = new int[2];
            boolean missing = false;
            try {
                if (!f.isFile()) {
                    missing = true;
                } else {
                    b = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f), (decoder, info, src) -> {
                        Size sz = info.getSize();
                        size[0] = sz.getWidth();
                        size[1] = sz.getHeight();
                        int[] t = PadFit.decodeSize(size[0], size[1], side, looks);
                        decoder.setTargetSize(t[0], t[1]);
                        // 软件位图：硬件位图配 BitmapShader 没查到能不能用、碰到软件 Canvas 会抛异常；软件的还能生 mipmap（小触控板上缩得很小时不闪）
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    });
                    b.setHasMipMap(true);
                    b.prepareToDraw();
                }
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                Log.w(TAG, "背景图解不了：" + e.getClass().getSimpleName());
                b = null;
            }
            Bitmap done = b;
            boolean gone = missing;
            MAIN.post(() -> {
                if (destroyed || gen.get() != g) return;
                pendingKey = null;
                if (gone) {
                    // 换机时没搬过来、被清掉了：当没设，静默清掉 Prefs（只在 Prefs 还指着这个文件时：期间可能已经换了新图）
                    if (name.equals(prefs.padBgFile())) prefs.clearPadBgFile();
                } else if (done == null && name.equals(bitmapFile)) {
                    return;   // 同一张图想解大一点没成（内存不够之类）：接着画手上那张小的
                }
                bitmap = done;
                bitmapFile = done != null ? name : null;
                srcW = size[0];
                srcH = size[1];
                listener.onPadImageChanged();
            });
        });
    }

    /**
     * 画到一块触控板上：clip = 圆角矩形（和触控板底色同一个形状），place = 摆图的内框（边框以内）。
     * 按内框的宽高挑哪套设置（分开设时）。没有图返回 false（什么都没画）。
     */
    boolean draw(Canvas c, RectF clip, float radius, RectF place, Brush brush) {
        if (bitmap == null) return false;
        return drawImage(c, clip, radius, place, bitmap, srcW, srcH, cfg.pick(place.width(), place.height()), cfg.alphaPct, 1, brush);
    }

    /** 每块触控板一个（两块触控板同一帧里各画各的变换）：自己的 Paint、shader、变换，位图是共用的那张。 */
    static final class Brush {
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        final Matrix matrix = new Matrix();
        BitmapShader shader;
        Bitmap bitmap;
        Shader.TileMode mode;

        /** 图没了：放掉对位图的引用（不然位图要等这块触控板下次画才放）。 */
        void clear() {
            shader = null;
            bitmap = null;
            mode = null;
            paint.setShader(null);
        }
    }

    /**
     * 照 PadFit.place 画一张图：设置页的预览也用它（unit = 预览比真触控板缩小的比例），所以预览和实际一样。
     * 用 shader 画圆角矩形（边缘有抗锯齿），不用 clipPath。返回 false = 盒子或图是空的，什么都没画。
     */
    static boolean drawImage(Canvas c, RectF clip, float radius, RectF place, Bitmap bmp, int srcW, int srcH,
                             PadFit.Look look, int alphaPct, double unit, Brush brush) {
        PadFit.Placement p = PadFit.place(place.width(), place.height(), srcW, srcH, bmp.getWidth(), bmp.getHeight(), look, unit);
        if (p == null) return false;
        Shader.TileMode mode = p.tile ? Shader.TileMode.REPEAT : Shader.TileMode.DECAL;
        if (brush.shader == null || brush.bitmap != bmp || brush.mode != mode) {
            brush.shader = new BitmapShader(bmp, mode, mode);
            brush.shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
            brush.bitmap = bmp;
            brush.mode = mode;
        }
        brush.matrix.setScale((float) p.sx, (float) p.sy);
        brush.matrix.postTranslate((float) (place.left + p.dx), (float) (place.top + p.dy));
        brush.shader.setLocalMatrix(brush.matrix);
        brush.paint.setShader(brush.shader);
        brush.paint.setAlpha(Math.round(alphaPct * 255 / 100f));
        c.drawRoundRect(clip, radius, radius, brush.paint);
        return true;
    }
}
