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
- "오늘의 한 줄" rotates through the `maxims` array by day of year. The static text in `<p id="maxim">` is only the no-JS fallback, so keep it equal to one of the entries.
- Section numbers (`<span class="no">`) are hand-numbered. Renumber them when inserting or removing a section.
- Theme: dark (black/gold) is the default on bare `:root`; the light palette (ivory/gold) applies under `prefers-color-scheme: light` and `[data-theme="light"]`. Use the color tokens, not literal colors.

## Voice and audience of the letter

- The writer is a fictional mentor persona, "The Chairman", speaking in a warm mentor register (자네, ~하게, ~일세). Never attribute words to a real person.
- The reader is an ordinary office worker who answers a hospital's main phone line (reservations, simple problem-solving, routing calls to departments). They are still figuring out their direction and are not running a business. An earlier version that pushed launching products felt overwhelming to them, so keep the advice small, concrete, and doable now.
- Context the advice draws on (from their Notion): Christian faith (prayer, Bible study), preparing for a first child, learning English, piano, and hanja, reading 『부의 추월차선』 and 『신삼국지』, and making PDF planners with Claude.
- Any advice about using Claude at work must say to keep patient information (names, phone numbers, medical details) out of it.

## Web copy

A viewable copy of the letter is also published as a claude.ai artifact. It is derived from `index.html` and must differ in three ways:
- No `<!doctype>`/`<html>`/`<head>`/`<body>` wrapper tags.
- Side padding written with `max(16px, env(safe-area-inset-*))`.
- The footer's `window.close()` button replaced by a link, because `window.close()` does nothing there.

Regenerate and republish that copy whenever the letter content changes.
