#!/bin/zsh
cd -- "$(dirname -- "$0")" || exit 1
python3 tools/restore.py  "$@"
fmtv_exit=$?
read -r "?按 Enter 關閉。"
exit "$fmtv_exit"
