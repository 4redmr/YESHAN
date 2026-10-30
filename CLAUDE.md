# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

A single personal project, `millionaire-advice/`: "억만장자의 아침 서신" (The Chairman's Morning Letter), a static HTML page that opens automatically every day at 07:00 and at every logon on the owner's computer. There is no build step, package manager, linter, or test suite.

- `index.html` is fully self-contained (inline CSS/JS; Google Fonts with serif fallbacks). Preview it by opening the file in a browser.
- `install-windows.bat` → `install-windows.ps1` registers a Task Scheduler task named `MillionaireAdvice` (daily 07:00 trigger + at-logon trigger with a 20s delay) that opens `index.html` in Edge/Chrome `--app` mode, falling back to `explorer.exe`. `uninstall-windows.bat` removes it.
- `install-mac.sh` writes a launchd agent `com.yeshan.millionaire-advice` (`RunAtLoad` + 07:00). `--uninstall` removes it.
- Both installers store the **absolute path** of `index.html`, so moving the folder requires reinstalling (README tells the user this).

A second project, `free-time/` ("오늘 나의 시간"; it was "오늘 남은 시간" until November 2026), is a small installable web app (PWA) the user wants to try selling. It shows "나의 시간", the time left until bed after work or school and travel, and keeps a daily log in `localStorage`. Call that time 나의 시간, never 자유 시간 or 여유: the user pointed out that many people feel they have no free time, and the hours outside work or school go to study, practice, prayer, and family.
- Files: `index.html` (inline CSS/JS), `manifest.webmanifest`, `sw.js`, `icons/`. Install and the service worker need an https host (e.g. Netlify Drop); they don't work from `file://`.
- Live at https://namun-sigan.netlify.app (Netlify, uploaded by hand). To ship a change, send the user a zip of `free-time/`; they unzip it and drag the folder onto the site's Deploys tab. This sandbox's network can't reach netlify.app, so ask the user to check the live site on their phone.
- Bump `VERSION` in `sw.js` whenever any cached file changes.
- The daily reminder is a repeating calendar event (.ics download or Google Calendar link), because a closed web app can't be woken on a schedule without a push server. The in-app notification only fires while the app is running.
- Modes (`settings.mode`): 💼 직장인 (근무/출퇴근), 🎒 학생 (수업/등하교), 📚 수험생·공부 (학원·독서실/오가는 길), ✍️ 직접 정하기 (user-typed names). Modes only relabel the busy block and travel (elements with `data-l`, labels from `L()`); the math is the same. First run shows a welcome card to pick one; on a fresh install a mode also sets starting tasks and times, later switches only rename. 나의 하루 is a folded card with a one-line summary. 집중 설정 has 재는 동안 화면 켜 두기 (Screen Wake Lock, default on).
- The plan and competitor review (열품타, Forest, Focus To-Do, 루티너리, Toggl) is the Notion page 📋 기획서 under ⏳ 나의 시간 앱. Next candidates: per-task daily goals, streak + month calendar, per-weekday schedules; skipped on purpose: app blocking, rankings, accounts/servers. The user turned down a personal-goals feature in both forms offered (goals with deadlines and weekly targets, then "wishes" with growing plants and a 5-minute nudge): they felt it was clichéd and distant for people who can't plan their time, and want the app to stay a simple day-by-day record. Don't propose planning, quota, or goal-setting features again unless they ask; existing per-task goals stay optional and off unless set.
- Schedule for a date comes from `dayPlan(key)`: a change saved for that date (`overrides[date]`: optional per-day `mode`, preset kind, times, off), then the weekday rule for the main mode (`settings.weekly[dow]`: same / custom hours / off, edited under 나의 하루 → 📆 요일마다), then the usual settings. Each mode keeps its own hours (`settings.modeTimes`); switching the main mode parks the old mode's hours and restores the new one's. 🧳 여행·특별한 날 is a per-day-only mode.
- "오늘만 다르게" (button under 나의 하루) edits any date from today on (date picker): pick that day's mode, a preset (names follow that day's mode: 반차/수업 없음/자유…), or times, and lists the days already planned. Changes for past dates are pruned after 60 days; it is included in backups.
- Tasks can have a daily goal (`settings.goals[name]`, set in the 편집 dialog together with the name); the row shows `n분 / goal분 ✅` and a progress bar. 기록 한눈에 shows the 7-day stats, a streak (days in a row with any minutes, counting from today or yesterday), and a month calendar shaded by minutes; tapping a day opens 기록 고치기.
- Daily log: the 할 일 card (right under the remaining-time card) lists the user's tasks (`settings.acts`, default 독서/공부/휴식/기도/운동). Tapping a task starts a timer, tapping again stops it; only one runs at a time. The timer stores only its start time (`ft.timer`), so it keeps counting while the app is closed, and its minutes go to the day it started (`records[date].acts`, minutes with one decimal). While it runs, a sticky focus banner shows the time and progress toward an optional focus goal (none/15/25/50 min; the default in 집중 설정 is 25, each task can override it in the 편집 dialog via `settings.focusGoals[name]`, and 휴식 starts with none); at the goal it alerts once, only while the app is open: 무음, 진동 (default; no vibration on iPhone), or 소리 (Web Audio beep). If the timer is still on past the user's bedtime (`settings.sleep`), the next open assumes they fell asleep and asks for the real minutes (default: start to bedtime); otherwise after 3 hours it asks whether they are still going. Any day's minutes can be corrected in a 기록 고치기 dialog (today's button or 고치기 on each 지난 기록 row), which also deletes a whole day; 되돌리기 works on past days too. Each day's record keeps a change log (`records[date].log`, last 60 lines: kind timer/manual/edit/undo, minutes or from→to, optional reason); badges show how each task's minutes came in (⏱ timer, ✋ 직접, ✏️ 고침; a fix wins), the 기록 고치기 dialog takes a reason (chips or typed) and shows the log, the sleep and 3-hour prompts log their corrections with a reason, and the CSV has a 고친 기록 column. In 편집 mode, tapping a task renames it (✏️) everywhere, including past records and a running timer; renaming onto an existing task merges the two; ✕ removes it from the list but keeps past records. The old +5/+10/+15/+30/+60 buttons live under "깜빡했나요? 시간 직접 더하기".
- 📖 사용 설명서: a card at the bottom (linked from "📖 사용법" next to the date) with one folded section per feature, in plain Korean. Update it whenever a feature changes, and keep it free of the user's personal links since the app is meant for others.
- 📝 메모장: one free-form note (`ft.memo`), separate from the daily records, kept until the user clears it, folded by default, and included in backups. An optional one-line memo goes with them. The line sent to Notion is `나의 시간 … · 쓴 시간 … (독서 40분, 기도 15분) · 메모` (older rows start with `자유`); the user's Notion column is named 나의 시간 too.
- Related things also use the new name: the Notion page 📊 주간 나의 시간 요약 (id `3fc3d8e501e7819a93e4e4d3f40ae3de`) that the Sunday scheduled task 주말 나의 시간 요약 writes into, and the Google Calendar events 오늘 나의 시간 기록하기 (daily 22:30) and 📊 이번 주 나의 시간 요약 보기 (Sundays 20:00).
- The user is a beginner who got lost when replies mixed in English or technical steps. Explain in plain Korean, keep steps few, and say exactly where to tap.
- "기록 보내기" opens the phone's share sheet with a friendly multi-line summary (KakaoTalk, memo apps), falling back to copy. The 백업 card saves all settings and records as JSON in a `.txt` file (Chrome only shares a few file types) via the share sheet or a download, and restores by merging: dates already on the phone are kept. Keep download file names ASCII; Korean names came out as "download" in Chromium.
- "노션에 저장하기" (always shown; tapping it without an address opens the setup box) copies today's line and opens the Notion page whose address the user saved in the app. No Notion URL or ID is hardcoded, because the app is meant to be sold to others.
- Notion page **⏳ 나의 시간 앱** (under 자기개발 홈, id `3fc3d8e501e781dd803ecc00a9fd2e4e`) holds the three feedback questions and **🗣️ 피드백 기록** (`collection://1dc7161c-c33b-4427-87fe-331e470a7435`): one row per person the app was sent to. The user fills it in, so read it when planning the next version and don't overwrite their answers.

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
- Reminders: put them in the user's Google Calendar (primary calendar `4redmr@gmail.com`, via the Notion calendar connector), where the phone reliably alerts. A scheduled task that only replies with text ran on time but did not reach the phone as a push notification.
- Quiet times for reminders and scheduled tasks (KST): Sundays before 16:00 (church), and Wednesdays 18:00 to 20:30 (Wednesday service). Never schedule anything to fire then.
- Any advice about using Claude at work must say to keep patient information (names, phone numbers, medical details) out of it.

## Notion record system

The letter's "오늘의 기록 남기러 가기" button opens the Notion page **회장님과의 동행** (under 자기개발 홈, id `3fc3d8e501e781e2b16fcf52d69d2ccc`). Every piece of advice is meant to be recorded there, so when advice is added or changed in the letter, update the Notion databases too:
- **📜 회장님의 조언 기록** (`collection://4fec76a5-0eb3-4182-8ec9-bef02a89a1cb`): one row per advice item. Fields: 조언, 영역 (신앙/마음/가정/일과 방향/배움/돈 관리/클로드), 상태 (아직/실천 중/습관이 됨), 핵심 한 줄, 오늘 해 볼 일, 받은 날, 출처, 순서. Add new rows as 상태 = 아직, and never overwrite the 상태 or 내 메모 fields the user has filled in.
- **✅ 오늘의 동행 기록** (`collection://0528d17a-f793-437a-a298-7b7276a25443`): the user's daily log (prayer/Bible/exercise checks, 뿌듯했던 순간, 지쳤던 순간, 해낸 일, 감사 세 가지, and 나의 시간 (renamed from 남은 시간), which the user pastes from the free-time app). This is the user's data; read it for weekly reviews but don't edit it.
- **💰 돈 관리 첫걸음** (`collection://475b6366-57c9-4b7d-b6c4-f6783543fb3e`): ordered beginner money steps with 완료 checkboxes.
- **🪜 사다리 한 칸씩** (`collection://3b75a626-8fa3-443f-82bc-5a12cb154998`, on the child page 수입의 사다리, id `3fc3d8e501e78181b322ec9e827c5d7d`): checklist for Chapter Ⅲ. Each step is in one of four 칸 (1 새는 돈 막기 / 2 회사에서 더 받기 / 3 월급 밖 첫 1만 원 / 4 모은 돈 불리기). Income advice must stay realistic: say to check the hospital's 겸직 rules before any side income (their contract has no 겸직 clause; the 취업규칙 has no 겸직 article either, but its 제114조 lists as grounds for discipline 16호 taking part in running another business or providing labor without the company's permission, 17호 using the company's name without permission, and 21호 leaking company secrets, so free sharing is fine but get written permission before any income, never use the hospital's name, and keep internal hospital information out), give no specific investment picks, and point to official sites for benefit amounts. The steps have a 한 달 효과 (won) field for the monthly amount each step saved or earned.
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
