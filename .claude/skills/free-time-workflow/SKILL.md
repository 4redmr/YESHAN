---
name: free-time-workflow
description: How to work on the free-time app ("오늘 나의 시간", free-time/ in this repo) with its owner — the way every change is designed, checked, shipped and explained, distilled from many rounds of feedback. Use this whenever the user asks for anything about the free-time app, the 나의 시간 친구 pet, its rooms and shop, deploying it to Netlify (namun-sigan), getting AI or user feedback on it, or selling it, even if they only say "앱", "고쳐줘", "올려줘", "친구", "강아지" or send a screenshot of it.
---

# Working on 오늘 나의 시간 with its owner

The owner is a beginner who builds only by talking to Claude, often from a phone. They know exactly what they like when they see it, and much less what they want before they see it. Most of the rules below come from moments where a reply confused them or a change wasn't what they pictured. CLAUDE.md describes the app itself (features, data keys, Notion pages); this skill is about how to work.

## Talking to the user

- Reply in plain, warm Korean, always. One English reply ("갑자기 영어로..") upset them; even technical summaries from subagents must be retold in Korean.
- No jargon. Say what to tap and where ("⚙️ 설정 → 백업 → 백업 저장"), a few steps at a time, then ask for a screenshot of the result before the next steps.
- When a word is unavoidable (PR, 백업 파일, 넷리파이), explain it with an everyday picture ("앱이 사는 집", "변경 신청서") the first time.
- Be honest about limits: if YouTube, the Play Store or a blog can't be opened from here, say so and ask for screenshots instead of guessing what a page says.
- End with one clear next step or a short numbered choice, not an open survey.

## Before building anything visual or structural

- Show first, then build. For a new look, layout or character, make a quick prototype (a standalone HTML in the scratchpad), screenshot it with Playwright, and send the picture — a GIF when motion matters — and ask "이 느낌이 맞나요?". Rebuilding the whole app on a guess cost several rounds ("내가 생각했던 모습이 아니야").
- When they say something looks wrong but not why, offer numbered guesses (그림이 섞여 보여요 / 물건이 둥둥 떠 있어요 / 너무 꽉 차요 …) and ask for a reference image. They often answer "다 알고 있네".
- Taste rules learned so far:
  - One pastel line-art style for everything in the friend's world (`#333` pen line, soft fills), modelled on 고양이는 정말 귀여워 but in colour. No emoji inside the picture.
  - Animals look real but cute, seen from the side, walking. Legs straight and narrowing, joined smoothly to the body; no bent hind legs or thigh bulges ("엉덩이 같아"), no lumps that look sore ("아파 보여").
  - Rooms should fill up 아기자기하게 — add things in sensible places (floor, shelf, wall), never floating.
  - Keep screens simple: the home tab is for every day; everything else lives in the other tabs.
- Keep both looks available: the plain text look and the friend look (설정 → 나의 시간 친구 보기). Before a big redesign, save the current version (a zip from `git archive <commit> free-time`) so nothing they liked is lost.
- Respect the product choices in CLAUDE.md: say 나의 시간 (never 자유 시간/여유), no goal/plan/quota features, no accounts, servers, rankings or app blocking.

## Making a change

1. Edit `free-time/index.html` (and `sw.js` when needed). Match the surrounding code style.
2. Use the in-app `ask()` dialog for every question; never `confirm()`/`prompt()`. Get Korean particles right (`eul()` gives 을/를; write messages so they read naturally, e.g. "모자를 살까요?").
3. Update the card's help box (`#help-<key>`) when its feature changes. Keep it short and free of the owner's personal links or Notion IDs — the app is for strangers.
4. Bump `VERSION` in `free-time/sw.js` whenever a cached file changes, or installed phones keep the old version.
5. Check it like a user would, with Playwright (Chromium is preinstalled; `require('playwright')`):
   - 390×844 and 360×640, light and dark (`colorScheme`);
   - a fresh user (empty localStorage) and a seeded user (records under `ft.records`, settings under `ft.settings`);
   - click through the flow you changed, collect `pageerror`s, take screenshots, and actually look at them before saying it works.
6. Update CLAUDE.md with what changed and why (the next session starts from it).
7. Commit with a clear message and push to the working branch.
8. Ship: `zip -rq <scratchpad>/free-time.zip free-time` from the repo root and send it with SendUserFile, together with a screenshot or GIF of the result and a short Korean caption ("… + 새 앱 파일 (v36)").
9. In the reply: what changed (in their words), what to tap to see it, the version number, and one next step.

## Getting feedback on the app

- When they ask another AI to review ("다른 AI한테 물어봐"), launch a subagent with full context (target users, the owner's deliberate choices, what earlier reviews already fixed) and a specific angle each time: first-time user, personas living a day (office worker who prays, exam student, new mother), a pre-release check for blockers only, or a monetization advisor. Ask for screenshots and real repro steps.
- Treat findings as claims: check each one in the code or in a browser before fixing, and tell the user which ones were verified and which were not (e.g. "0분 기록은 테스트 데이터 때문이라 실제 문제가 아니에요").
- Say honestly that AI reviews never run out of nitpicks and can't tell whether real people keep using or paying. Steer each later round toward blockers, and toward real users (🗣️ 피드백 기록 in Notion; the owner has few acquaintances, so suggest strangers: 디스콰이엇, Threads/Instagram, prayer or study open chats).
- Summaries for the user: 좋은 점 → 고칠 점 (most important first) → my opinion → "어떤 걸 고칠까요?". They usually answer "다 고쳐줘"; then fix everything that holds up and report back the same way.

## Deploying (the user does it by hand)

- The site is Netlify project `namun-sigan` (https://namun-sigan.netlify.app). This sandbox can't reach it, so the user checks on their phone.
- Before any deploy that might change the address: have them back up on the phone first (설정 → 백업 → 백업 저장 → 카카오톡 나와의 채팅) and never delete the installed app.
- Steps live on the Notion page 🚀 사이트에 올리는 순서: unzip → Projects → drop the `free-time` folder on "Upload your project files" → Project configuration → Change project name → namun-sigan. Later updates: namun-sigan → Deploys → drop the new folder.
- Ask for a screenshot at each step; Netlify's screens change and may be in Korean or English.

## Money and the workplace

- The owner works at a hospital. Before any income — sales, donations, anything — they need the hospital's written permission; never use the hospital's name and keep hospital or patient information out of everything. A draft request letter is on the Notion page 💰 유료화 계획.
- Give realistic advice: no investment picks, point to official sources (국세청 126, 홈택스, platform seller guides) for tax and registration, and flag anything uncertain.
- Bible verse text (개역개정) is copyrighted; references like "요한복음 3:16" are fine in anything sold.

## Notion and scheduling

- Record plans and steps as pages under ⏳ 나의 시간 앱 so the owner can follow them later; read 🗣️ 피드백 기록 but never overwrite their answers.
- Never schedule anything for Sundays before 16:00 or Wednesdays 18:00–20:30 KST (church).
