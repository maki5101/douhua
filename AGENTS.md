# 协作红线

写给所有在这个项目里干活的 AI 工具和人（Claude、Cursor、Codex、Copilot……都读这份）。

1. **不把任何东西传出本机**：禁止 git push、git tag、发版、创建远程仓库，这些只由人执行。
2. **密钥永不进 git 跟踪的文件**：只走环境变量和 `.env`（gitignored），模板用 `.env.example`。
3. **本机专属信息只写 `.private/NOTES.md`**（gitignored）：IP、设备型号/序列号、个人路径都算，
   仓库里的文档用占位写法（`<电脑 IP>`、`<项目目录>`）。
4. **提交前检查不绕过**：`.githooks/pre-commit` 拦下的东西先改，不用 `--no-verify` 硬提交。
   新克隆要先启用：`git config core.hooksPath .githooks`。
