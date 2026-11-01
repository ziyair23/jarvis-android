# Jarvis Lab — idea backlog

Score = daily impact ×3 + fits his taste ×2 + cost + buildable now + safety (each 1–5, max 40).
Notes that start with "Marcus:" always win.

Marcus (2026-11-01): no more DoorDash features from the lab. Pitch other areas.

| id | title | theme | pitch | score | status |
|----|-------|-------|-------|-------|--------|
| L1 | Offer diary + best hours | Business partner | Jarvis quietly keeps every DoorDash offer he judges (pay, miles, minutes, time of day) on the phone, so "when are my best hours?" gets a real answer from his own data. | 37 | rejected (Marcus: kill) |
| L2 | Per-hour offer judging | Business partner | The offer call-out adds dollars per hour (from the minutes or "deliver by" time on the card), and the verdict uses both per mile and per hour, so long-wait short-distance orders stop looking good. | 39 | rejected (Marcus: kill) |
| L3 | Auto-log earnings | Business partner | When a dash ends, Jarvis reads the Dasher summary screen and logs the total itself ("Logged 54 dollars, sir"), no voice entry needed. | 37 | rejected (Marcus: no more DoorDash) |
| L4 | Dash mileage log | Business partner | Counts miles driven while online in Dasher for tax time (2026 IRS rate), on the phone only. | 31 | rejected (Marcus: no more DoorDash) |
| L5 | Shift wrap line | Running his day | One sentence when he goes offline: earned, per hour, days left to the Macan. Risky for taste (he dropped the spoken brief). | 29 | rejected (Marcus: no more DoorDash) |
| L6 | Pursuit idea vault | Business partner | "Jarvis, Pursuit idea: …" saves design and drop ideas with dates; "read my Pursuit ideas about hoodies" brings them back. | 32 | new |
| L7 | Social pulse | Business partner | "How's my YouTube doing?" pulls views and subs; Instagram/TikTok later (they need business accounts and tokens). | 28 | new |
| L8 | No-signal mode in the car | Speed and reliability | With no signal, reflexes still work and anything that needs Claude is held and answered when signal comes back, instead of failing. | 34 | ready-for-Marcus (independent test 2026-11-01: PASS; both recommended fixes applied and re-checked in L17-2026-11-01-indep-r2.md) |
| L9 | Home-after-dash scene | Running his day | After the evening shift, his Google Home lights go warm when he gets home (Home Automation API). | 28 | new |
| L10 | Slow-restaurant memory | Business partner | Say "this place is slow" at a pickup; next time an offer comes from that restaurant, Jarvis warns him in the call-out. | 36 | rejected (Marcus: no more DoorDash) |
| B1 | Big bet: a judge that learns him | Big bet | Builds on L1: once Jarvis knows which offers he accepts at which hours, the verdict adapts to him instead of fixed rules. | — | rejected (Marcus: no more DoorDash) |
| B2 | Big bet: Pursuit business desk | Big bet | A weekly business desk for Pursuit: sales, top posts, what to drop next, drafted for him to approve. Needs his store platform first. | — | idea |
| L11 | Make your own protocols | Running his day | "Jarvis, make a protocol called gym: quiet the phone, play my workout playlist, lights off." Then "gym protocol" runs it. Only phone, music, lights and timer steps: never texts, calls or taps. | 38 | rejected (Marcus: kill) |
| L12 | Pursuit idea vault | Business partner | Replaces L6 with a sharper version: "Pursuit idea: …" saves it tagged by type (design, drop, post, collab); "what Pursuit ideas do I have for hoodies?" reads back the best matches. | 32 | new |
| L13 | YouTube pulse | Business partner | "How's my channel doing?" → subscribers, views, and how the newest video is doing vs his usual. YouTube Data API is free (10,000 units a day); an API key typed into the app, never the repo. | 33 | new |
| L14 | Say it once, Jarvis learns the shortcut | Running his day | When he asks for the same 2–3 things together a few times, Jarvis offers once: "Want that as a protocol, sir?" Builds on L11. | 31 | rejected (built on L11) |
| L15 | Morning-free quiet start | Conversation | Jarvis greets with the one thing that matters only when he opens him first each day (a reminder due, a missed call), never a brief. | 27 | new |
| L16 | Memory check-in on request | Conversation | "What do you know about me?" reads back his saved facts in two lines, "forget the one about…" removes it. Makes memory feel trustworthy. | 30 | new |
| L17 | Phone-call notes | Running his day | After a call he can say "note that": Jarvis saves a one-line note tied to the caller and brings it up next time that person calls. | 33 | ready-for-Marcus (independent test round 2, 2026-11-01: PASS; 1 minor fix recommended, see lab/reports/L17-2026-11-01-indep-r2.md) |
| B3 | Big bet: Jarvis on the PC | Big bet | A small Windows companion: same Jarvis memory and voice, opens apps and files on his PC, drives video edits with him. Phone and PC share one brain. | — | idea |
| L18 | Who's calling, in context | Running his day | Builds on L17: when a known caller rings, Jarvis also says when they last spoke ("last spoke Tuesday"), from the phone's call log. On-phone, free. | 29 | new |
| L19 | Pursuit customer book | Business partner | Call notes tagged as Pursuit orders ("note for Marco: two large hoodies, paid") roll up into "what Pursuit orders are open?". On-phone, never sends. | 31 | new |
| L20 | Say that again | Conversation | "Say that again" / "what was that?" replays Jarvis's last spoken line instantly on the phone (no Claude, free, works with no signal); "the last bit" replays only the last sentence. For when a horn or the baby drowns him out. | 37 | testing |
| L21 | Hold on | Conversation | "Hold on" / "one sec" keeps the conversation open about 20 seconds without answering, so he can finish a turn or check something and carry on without saying "Jarvis" again. | 36 | new |
| L22 | Shoot list | Business partner | "Shot idea: hoodie on the bridge at dusk" saves to a shoot list (not brand ideas like L12, but what to film); "what's on my shoot list?" reads the three newest. On-phone. | 30 | new |
| L23 | Auto parking pin | Running his day | When the car Bluetooth drops, Jarvis quietly saves where the phone is; "where did I park?" opens the pin in Maps. Free, on-phone, needs location permission. | 31 | new |
| L24 | Quiet-house voice | Running his day | "House is asleep" (or after a set hour): Jarvis speaks softer on the phone only, no chimes, no Nest Hub, until morning or "house is up". | 32 | new |
| L25 | Spell it / slower | Conversation | Builds on L20: "spell that" reads a code, address or name letter by letter; "slower" replays at a slower voice speed. | 28 | new |
| B4 | Big bet: hand big app jobs to Android's own agent | Big bet | Android's Gemini Intelligence (I/O 2026) can carry out multi-step jobs inside apps. Jarvis stays the voice he talks to and hands those jobs over, instead of tapping screens itself; it still drafts and asks before anything is sent. | — | idea |

Research notes (2026-11-01, run 5): Gemini Intelligence on Android adds app automation and custom widgets (https://www.businesstoday.in/technology/story/google-introduces-gemini-intelligence-for-android-with-app-automation-auto-fill-and-more-features-531166-2026-05-12, https://gulfnews.com/technology/what-is-googles-gemini-intelligence-how-ai-agents-will-reshape-control-of-your-android-phone-1.500539178). Notification read-aloud apps such as SpeakThat run fully on the phone (https://f-droid.org/packages/com.micoyc.speakthat/). Creator tools focus on hook and shot planning (https://renderfire.com/resources/fashion/youtube-hooks).
