#!/data/data/com.termux/files/usr/bin/bash
cd "$(dirname "$0")" || exit 1

NAME="HousouApp"
USER="Sekiguchi-Takashi"
MSG="${1:-update}"

TOKEN="$(git config --global github.token)"
if [ -z "$TOKEN" ]; then
  printf '%s\n' "github.token が未設定です。git config --global github.token で登録してください。"
  exit 1
fi

curl -s -o /dev/null \
  -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  https://api.github.com/user/repos \
  -d "{\"name\":\"$NAME\",\"private\":true}"

if [ ! -d .git ]; then
  git init
fi

git config user.name "$USER"
git config user.email "$USER@users.noreply.github.com"

git remote remove origin >/dev/null 2>&1
git remote add origin "https://$USER:$TOKEN@github.com/$USER/$NAME.git"

git add -A
git commit -m "$MSG" || printf '%s\n' "コミット対象の変更はありません。"
git branch -M main
git push -u origin main

printf '%s\n' "完了: https://github.com/$USER/$NAME/actions"
