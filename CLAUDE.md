# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A single personal project, `millionaire-advice/`: "억만장자의 아침 서신" (The Chairman's Morning Letter), a static HTML page that opens automatically every day at 07:00 and at every logon on the owner's computer. There is no build step, package manager, linter, or test suite.

- `index.html` is fully self-contained (inline CSS/JS; Google Fonts with serif fallbacks). Preview it by opening the file in a browser.
- `install-windows.bat` → `install-windows.ps1` registers a Task Scheduler task named `MillionaireAdvice` (daily 07:00 trigger + at-logon trigger with a 20s delay) that opens `index.html` in Edge/Chrome `--app` mode, falling back to `explorer.exe`. `uninstall-windows.bat` removes it.
- `install-mac.sh` writes a launchd agent `com.yeshan.millionaire-advice` (`RunAtLoad` + 07:00). `--uninstall` removes it.
- Both installers store the **absolute path** of `index.html`, so moving the folder requires reinstalling (README tells the user this).

A second project, `free-time/` ("오늘 남은 시간"), is a small installable web app (PWA) the user wants to try selling. It shows today's free time after sleep, work, and commuting, and keeps a daily log in `localStorage`.
- Files: `index.html` (inline CSS/JS), `manifest.webmanifest`, `sw.js`, `icons/`. Install and the service worker need an https host (e.g. Netlify Drop); they don't work from `file://`.
- Live at https://namun-sigan.netlify.app (Netlify, uploaded by hand). To ship a change, send the user a zip of `free-time/`; they unzip it and drag the folder onto the site's Deploys tab. This sandbox's network can't reach netlify.app, so ask the user to check the live site on their phone.
- Bump `VERSION` in `sw.js` whenever any cached file changes.
- The daily reminder is a repeating calendar event (.ics download or Google Calendar link), because a closed web app can't be woken on a schedule without a push server. The in-app notification only fires while the app is running.
- Daily log: the user picks an activity chip (editable list, default 독서/공부/휴식/기도/운동) and taps +5/+10/+15/+30/+60 minute buttons; minutes add up per activity and save immediately (`records[date].acts`). An optional one-line memo goes with them. The line sent to Notion is `자유 … · 쓴 시간 … (독서 40분, 기도 15분) · 메모`.
- The user is a beginner who got lost when replies mixed in English or technical steps. Explain in plain Korean, keep steps few, and say exactly where to tap.
- "기록 보내기" opens the phone's share sheet with a friendly multi-line summary (KakaoTalk, memo apps), falling back to copy. The 백업 card saves all settings and records as JSON in a `.txt` file (Chrome only shares a few file types) via the share sheet or a download, and restores by merging: dates already on the phone are kept. Keep download file names ASCII; Korean names came out as "download" in Chromium.
- "노션에 저장하기" (always shown; tapping it without an address opens the setup box) copies today's line and opens the Notion page whose address the user saved in the app. No Notion URL or ID is hardcoded, because the app is meant to be sold to others.
- Notion page **⏳ 남은 시간 앱** (under 자기개발 홈, id `3fc3d8e501e781dd803ecc00a9fd2e4e`) holds the three feedback questions and **🗣️ 피드백 기록** (`collection://1dc7161c-c33b-4427-87fe-331e470a7435`): one row per person the app was sent to. The user fills it in, so read it when planning the next version and don't overwrite their answers.

## Constraints that are easy to break

- `install-windows.ps1` must stay **ASCII-only**: Windows PowerShell 5.1 misreads a UTF-8 file without a BOM, so put no Korean text in it.
- `.gitattributes` forces CRLF for `*.bat`/`*.ps1` and LF for `*.sh`. Keep it that way.
- Content length is a user requirement: Chapter Ⅰ (`<section id="claude">`, Claude Code advice for a beginner) must be **≥ 4,000 chars** and Chapter Ⅱ (`<section id="life">`, life/direction advice) **≥ 3,000 chars**, counted **excluding whitespace**. Chapter Ⅲ (`<section id="money">`, 수입의 사다리: four steps for growing income on a base salary) has no length minimum. The page footer (`#count`) shows these counts at runtime. Check after any edit:
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
- Their own words for their hope: to help people who are struggling, to share their faith, to save lives, and to become someone who can give freely without running short. They asked to be grown and guided toward it. Tie advice back to this hope, but in small steps they can take now, not big leaps.
- Any advice about using Claude at work must say to keep patient information (names, phone numbers, medical details) out of it.

## Notion record system

The letter's "오늘의 기록 남기러 가기" button opens the Notion page **회장님과의 동행** (under 자기개발 홈, id `3fc3d8e501e781e2b16fcf52d69d2ccc`). Every piece of advice is meant to be recorded there, so when advice is added or changed in the letter, update the Notion databases too:
- **📜 회장님의 조언 기록** (`collection://4fec76a5-0eb3-4182-8ec9-bef02a89a1cb`): one row per advice item. Fields: 조언, 영역 (신앙/마음/가정/일과 방향/배움/돈 관리/클로드), 상태 (아직/실천 중/습관이 됨), 핵심 한 줄, 오늘 해 볼 일, 받은 날, 출처, 순서. Add new rows as 상태 = 아직, and never overwrite the 상태 or 내 메모 fields the user has filled in.
- **✅ 오늘의 동행 기록** (`collection://0528d17a-f793-437a-a298-7b7276a25443`): the user's daily log (prayer/Bible/exercise checks, 뿌듯했던 순간, 지쳤던 순간, 해낸 일, 감사 세 가지, and 남은 시간, which the user pastes from the free-time app). This is the user's data; read it for weekly reviews but don't edit it.
- **💰 돈 관리 첫걸음** (`collection://475b6366-57c9-4b7d-b6c4-f6783543fb3e`): ordered beginner money steps with 완료 checkboxes.
- **🪜 사다리 한 칸씩** (`collection://3b75a626-8fa3-443f-82bc-5a12cb154998`, on the child page 수입의 사다리, id `3fc3d8e501e78181b322ec9e827c5d7d`): checklist for Chapter Ⅲ. Each step is in one of four 칸 (1 새는 돈 막기 / 2 회사에서 더 받기 / 3 월급 밖 첫 1만 원 / 4 모은 돈 불리기). Income advice must stay realistic: say to check the hospital's 겸직 rules before any side income (their 2026 employment contract has no 겸직 clause and defers to the hospital's 취업규칙, which they still need to read), give no specific investment picks, and point to official sites for benefit amounts. The steps have a 한 달 효과 (won) field for the monthly amount each step saved or earned.
- **📒 월별 가계 장부** (`collection://f9d1df6b-d6ff-47d4-8156-201e538f959e`, also on 수입의 사다리): one row per month with income and spending numbers. 총수입, 총지출, 남은 돈, and 저축률 are formulas. This is the user's data; read it for reviews but don't change their numbers. Its 구매 지출 rollup sums 지출 금액 from the linked rows of the user's own **🛒 사고 싶은 것** database (`collection://8d7643bb-8b90-4126-b1af-03aea43d18ae`, on 메모.체크리스트). There, 지출 금액 = 가격 once 구매완료 is checked, and the 가계 장부 relation picks the month. 구매 지출 is included in 총지출 and 남은 돈, so 생활비 should exclude those purchases. If asked to tidy up purchases, link each checked item to the ledger row for the month of its 구매일.

A child page **🎧 고객지원실** (id `3fc3d8e501e781bc92e4c880aceb47a5`) holds workplace counseling. Its principles: confirm facts before apologizing; if it was the user's mistake, apologize once with a fix; if not, state the facts and offer a solution; record yelling incidents factually. It has three databases:
- **📋 일어난 일 기록** (`collection://884a9621-15b6-476a-828d-ac77a14f45f0`): incidents, with 사실 확인 status, 받은 조언, and 다음엔 이렇게.
- **💬 상황별 대응 문장** (`collection://d4931591-7b6e-4922-a73f-a390fbb66485`): ready-to-say scripts by situation.
- **📞 지점별 진료 안내표** (`collection://f9168c1a-cca9-452c-b05a-0a9e70090c40`): weekday and Saturday availability of treatments per branch.

When counseling, read the latest incident first. Add new advice to that row's 받은 조언 field and new scripts to 상황별 대응 문장. Never record patient names, phone numbers, or medical details anywhere.

## Web copy

A viewable copy of the letter is also published as a claude.ai artifact. It is derived from `index.html` and must differ in three ways:
- No `<!doctype>`/`<html>`/`<head>`/`<body>` wrapper tags.
- Side padding written with `max(16px, env(safe-area-inset-*))`.
- The footer's `window.close()` button replaced by a link, because `window.close()` does nothing there.

Regenerate and republish that copy whenever the letter content changes.
