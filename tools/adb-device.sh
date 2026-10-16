# 选定要操作的那台手机，结果放进 ANDROID_SERIAL（adb 自己认这个变量，之后的 adb 命令都对准它）。
# 被 start-server.sh / stop-server.sh / selftest.sh / build.sh --install 用 source 引入，不单独运行。
#
# 用法：pick_device [序列号]
# 优先级：参数给的序列号 → 环境变量 ANDROID_SERIAL → 只连着一台就用它 →
#         连着多台时在终端里列出来让人选；不在终端里（被别的脚本调、没法问）就报错，说清楚怎么指定。

_adb_lines() {
    adb devices -l | tail -n +2 | sed '/^[[:space:]]*$/d'
}

# 一行 `adb devices -l` 的输出 → "序列号  型号"
_adb_describe() {
    local serial model
    serial="$(printf '%s\n' "$1" | awk '{print $1}')"
    model="$(printf '%s\n' "$1" | grep -o 'model:[^ ]*' | cut -d: -f2 || true)"
    printf '%s  %s' "$serial" "${model:-（型号未知）}"
}

_adb_show_not_ready() {
    local lines="$1" bad
    bad="$(printf '%s\n' "$lines" | awk '$2 != "device" {print "  " $1 "（" $2 "）"}')"
    if [ -n "$bad" ]; then
        echo "另外这些连着但用不了（unauthorized = 在手机上点\"允许 USB 调试\"；offline = 拔插一下或重新 adb connect）：" >&2
        echo "$bad" >&2
    fi
}

pick_device() {
    local want="${1:-${ANDROID_SERIAL:-}}"
    local lines ready count
    lines="$(_adb_lines)"
    ready="$(printf '%s\n' "$lines" | awk '$2 == "device"')"
    count="$(printf '%s\n' "$ready" | sed '/^$/d' | wc -l | tr -d ' ')"

    if [ -n "$want" ]; then
        if printf '%s\n' "$ready" | awk -v s="$want" '$1 == s {found = 1} END {exit !found}'; then
            export ANDROID_SERIAL="$want"
            return 0
        fi
        echo "指定的手机 $want 没连上（或没授权）。现在能用的有 $count 台：" >&2
        printf '%s\n' "$ready" | sed '/^$/d' | while IFS= read -r l; do echo "  $(_adb_describe "$l")" >&2; done
        _adb_show_not_ready "$lines"
        return 1
    fi

    if [ "$count" -eq 0 ]; then
        echo "没有连上的手机：USB 插好并在手机上允许调试，或者打开无线调试后 adb connect <手机 IP:端口>（端口每次会变）。" >&2
        _adb_show_not_ready "$lines"
        return 1
    fi

    if [ "$count" -eq 1 ]; then
        ANDROID_SERIAL="$(printf '%s\n' "$ready" | sed '/^$/d' | awk '{print $1}')"
        export ANDROID_SERIAL
        return 0
    fi

    # 连着多台
    local serials=() i=0 l choice
    echo "连着 $count 台手机：" >&2
    while IFS= read -r l; do
        [ -n "$l" ] || continue
        i=$((i + 1))
        serials+=("$(printf '%s\n' "$l" | awk '{print $1}')")
        echo "  $i) $(_adb_describe "$l")" >&2
    done <<< "$ready"
    if [ ! -t 0 ]; then
        echo "没法在这里问选哪台：加 -s <序列号>，或者先 export ANDROID_SERIAL=<序列号>。" >&2
        return 1
    fi
    read -r -p "选哪台？[1-$i] " choice
    case "$choice" in
        ''|*[!0-9]*) echo "没选，什么都没做。" >&2; return 1 ;;
    esac
    if [ "$choice" -lt 1 ] || [ "$choice" -gt "$i" ]; then
        echo "没有第 $choice 台，什么都没做。" >&2
        return 1
    fi
    export ANDROID_SERIAL="${serials[$((choice - 1))]}"
    return 0
}
