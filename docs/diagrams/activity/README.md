# Activity diagrams (PlantUML)

PlantUML source of the five main-workflow activity diagrams, with one swimlane per actor.
Each file is a straight transcription of the reviewed draw.io diagram (`Diagram/ACTIVITY/act-*.drawio`, English version). The PNGs in the report come from the draw.io files.

| File | Workflow |
|---|---|
| `act-1-payment.puml` | Parent / Teacher buys a plan through PayOS; the system marks the order PAID or FAILED and grants the plan (a late payment is still honoured). |
| `act-2-group-slot.puml` | Parent / Teacher creates a group and opens a slot; the child enters with code + PIN (wrong PIN loops back, the 5th miss locks the slot). |
| `act-3-play.puml` | Child plays a chapter; the game submits the chapter batch, and the system recomputes stats, grades mini-games and saves progress. |
| `act-4-quiz.puml` | Teacher sets up a class test or post-tests; the child answers and submits; the system grades it on the server and the teacher views the results. |
| `act-5-content.puml` | Editor drafts, the system checks endings, Reviewer approves or returns, Manager publishes or recalls a release. |

Render: `java -jar plantuml.jar -tsvg <file>.puml`.

Differences from the draw.io drawings, forced by PlantUML (the meaning is the same):

- Swimlanes are vertical columns in PlantUML; in draw.io they are horizontal rows.
- PlantUML draws a merge in the lane of its decision. As a result, the merge before "Mark the order PAID" (act-1) and the merge before "Show the PUBLISHED test" (act-4) appear in the first lane, not in System (BE).
- act-5: draw.io has one merge that both back-edges enter. PlantUML allows only one back-edge per loop, so it uses two nested `repeat` loops, which gives two merge diamonds one after the other.
