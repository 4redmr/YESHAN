# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A single personal project, `millionaire-advice/`: "억만장자의 아침 서신" (The Chairman's Morning Letter), a static HTML page that opens automatically every day at 07:00 and at every logon on the owner's computer. There is no build step, package manager, linter, or test suite.

- `index.html` is fully self-contained (inline CSS/JS; Google Fonts with serif fallbacks). Preview it by opening the file in a browser.
- `install-windows.bat` → `install-windows.ps1` registers a Task Scheduler task named `MillionaireAdvice` (daily 07:00 trigger + at-logon trigger with a 20s delay) that opens `index.html` in Edge/Chrome `--app` mode, falling back to `explorer.exe`. `uninstall-windows.bat` removes it.
- `install-mac.sh` writes a launchd agent `com.yeshan.millionaire-advice` (`RunAtLoad` + 07:00). `--uninstall` removes it.
- Both installers store the **absolute path** of `index.html`, so moving the folder requires reinstalling (README tells the user this).

## Constraints that are easy to break

- `install-windows.ps1` must stay **ASCII-only**: Windows PowerShell 5.1 misreads a UTF-8 file without a BOM, so put no Korean text in it.
- `.gitattributes` forces CRLF for `*.bat`/`*.ps1` and LF for `*.sh`. Keep it that way.
- Content length is a user requirement: Chapter Ⅰ (`<section id="claude">`, Claude Code advice for a beginner) must be **≥ 4,000 chars** and Chapter Ⅱ (`<section id="life">`, life/direction advice) **≥ 3,000 chars**, counted **excluding whitespace**. The page footer (`#count`) shows these counts at runtime. Check after any edit:
  ```bash
  python3 -c "import re,html;s=open('millionaire-advice/index.html',encoding='utf-8').read()
  for i in ['claude','life']:
      t=html.unescape(re.sub(r'<[^>]+>','',re.search(r'id=\"%s\">(.*?)</section>'%i,s,re.S).group(1)));print(i,len(re.sub(r'\s','',t)))"
  ```
- "오늘의 한 줄" rotates through the `maxims` array by day of year, and "오늘의 말씀" rotates through `verses` the same way. The static text in `<p id="maxim">` and `<p id="verse">` is only the no-JS fallback, so keep each equal to one of its array's entries. Verses are quoted from 개역개정. Keep the wording exact and use a ref for every verse.
- Section numbers (`<span class="no">`) are hand-numbered. Renumber them when inserting or removing a section.
- Theme: dark (black/gold) is the default on bare `:root`; the light palette (ivory/gold) applies under `prefers-color-scheme: light` and `[data-theme="light"]`. Use the color tokens, not literal colors.

## Voice and audience of the letter

- The writer is a fictional mentor persona, "The Chairman", speaking in a warm mentor register (자네, ~하게, ~일세). Never attribute words to a real person.
- The reader is an ordinary office worker who answers a hospital's main phone line (reservations, simple problem-solving, routing calls to departments). They are still figuring out their direction and are not running a business. An earlier version that pushed launching products felt overwhelming to them, so keep the advice small, concrete, and doable now.
- Context the advice draws on (from their Notion): Christian faith (prayer, Bible study), preparing for a first child, learning English, piano, and hanja, reading 『부의 추월차선』 and 『신삼국지』, and making PDF planners with Claude.
- Any advice about using Claude at work must say to keep patient information (names, phone numbers, medical details) out of it.

## Notion record system

The letter's "오늘의 기록 남기러 가기" button opens the Notion page **회장님과의 동행** (under 자기개발 홈, id `3fc3d8e501e781e2b16fcf52d69d2ccc`). Every piece of advice is meant to be recorded there, so when advice is added or changed in the letter, update the Notion databases too:
- **📜 회장님의 조언 기록** (`collection://4fec76a5-0eb3-4182-8ec9-bef02a89a1cb`): one row per advice item. Fields: 조언, 영역 (신앙/마음/가정/일과 방향/배움/돈 관리/클로드), 상태 (아직/실천 중/습관이 됨), 핵심 한 줄, 오늘 해 볼 일, 받은 날, 출처, 순서. Add new rows as 상태 = 아직, and never overwrite the 상태 or 내 메모 fields the user has filled in.
- **✅ 오늘의 동행 기록** (`collection://0528d17a-f793-437a-a298-7b7276a25443`): the user's daily log (prayer/Bible/exercise checks, 뿌듯했던 순간, 지쳤던 순간, 해낸 일, 감사 세 가지). This is the user's data; read it for weekly reviews but don't edit it.
- **💰 돈 관리 첫걸음** (`collection://475b6366-57c9-4b7d-b6c4-f6783543fb3e`): ordered beginner money steps with 완료 checkboxes.

## Web copy

A viewable copy of the letter is also published as a claude.ai artifact. It is derived from `index.html` and must differ in three ways:
- No `<!doctype>`/`<html>`/`<head>`/`<body>` wrapper tags.
- Side padding written with `max(16px, env(safe-area-inset-*))`.
- The footer's `window.close()` button replaced by a link, because `window.close()` does nothing there.

Regenerate and republish that copy whenever the letter content changes.
