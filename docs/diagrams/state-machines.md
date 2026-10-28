# FinTeen state machines (simplified, Review 2)

Mermaid transcription of the approved draw.io figures in `Diagram/STATE-MACHINE/sm-*-simple.drawio` (English versions;
the PNGs are used in the report). Same states, transitions, labels, initial and final pseudostates.
Label syntax: `event [guard]`; `when(...)` is a change event; `after (...)` is a time event.
Line breaks inside draw.io labels are written as a single space.

## Transaction lifecycle

A payment transaction starts PENDING and ends PAID; a FAILED transaction can still become PAID if payment is confirmed late.

```mermaid
---
title: state machine Transaction
---
stateDiagram-v2
    [*] --> PENDING : buy
    PENDING --> PAID : paymentConfirmed
    PENDING --> FAILED : cancel, linkExpired, paymentFailed
    FAILED --> PAID : paymentConfirmed
    PAID --> [*]
```

## LearnerSlot lifecycle

A learner slot is ACTIVE (UNLOCKED or temporarily LOCKED by wrong PINs) until it is archived or wiped; WIPED is terminal.

```mermaid
---
title: state machine LearnerSlot
---
stateDiagram-v2
    [*] --> ACTIVE : openSlot
    state ACTIVE {
        [*] --> UNLOCKED
        UNLOCKED --> LOCKED : wrongPin [5th in a row]
        LOCKED --> UNLOCKED : after (15 min), changePin
    }
    ACTIVE --> ARCHIVED : returnSlot, closeGroup
    ACTIVE --> WIPED : wipeSlot
    ARCHIVED --> WIPED : wipeSlot
    WIPED --> [*]
```

## RunState lifecycle

A child's run stays PLAYABLE, moving from NOT_STARTED through IN_PROGRESS to COMPLETED, and ends when the slot is archived or wiped.

```mermaid
---
title: state machine RunState
---
stateDiagram-v2
    [*] --> PLAYABLE
    state PLAYABLE {
        [*] --> NOT_STARTED
        NOT_STARTED --> IN_PROGRESS : submitChapter [chapter 1]
        IN_PROGRESS --> IN_PROGRESS : submitChapter [next chapter]
        IN_PROGRESS --> COMPLETED : when(all chapters of current release done)
        COMPLETED --> IN_PROGRESS : when(current release has more chapters)
        COMPLETED --> IN_PROGRESS : submitChapter [chapter 1, new attempt]
    }
    PLAYABLE --> [*] : when(slot archived or wiped)
```

The draw.io figure draws the initial arrow straight to NOT_STARTED inside PLAYABLE; here it is written as the
equivalent default entry (`[*] --> PLAYABLE`, then `[*] --> NOT_STARTED` inside), because Mermaid's default layout drops
the `PLAYABLE --> [*]` arrow otherwise. Same UML semantics.

## Quiz lifecycle

A quiz is either a teacher-made DRAFT that gets published or an auto-created post-test that starts PUBLISHED, and it closes when the due date passes.

```mermaid
---
title: state machine Quiz
---
stateDiagram-v2
    state entry_choice <<choice>>
    [*] --> entry_choice
    entry_choice --> DRAFT : [teacher creates a class test]
    entry_choice --> PUBLISHED : [child finished a chapter, class post-test on, none yet]
    DRAFT --> DRAFT : edit
    DRAFT --> [*] : delete
    DRAFT --> PUBLISHED : publish
    state PUBLISHED {
        [*] --> OPEN
        OPEN --> HIDDEN : postTestOff [post-test]
        HIDDEN --> OPEN : postTestOn
        OPEN --> OPEN : submit [once per child]
    }
    PUBLISHED --> CLOSED : when(due date passed)
    CLOSED --> [*]
```

`entry_choice` is the unnamed choice pseudostate (diamond) of the draw.io figure; Mermaid needs an identifier for it.
The draw.io figure has two final pseudostates (after `delete` and after CLOSED); Mermaid draws every top-level `[*]`
target as one node, which is equivalent in UML (final states in the same region mean the same thing).

## Content release lifecycle

The draw.io figure holds two state machines: ContentChange (a chapter draft moving through review to release) and GameRelease (a published release being current, superseded or recalled).

### ContentChange

```mermaid
---
title: state machine ContentChange
---
stateDiagram-v2
    [*] --> DRAFT : save
    DRAFT --> DRAFT : save
    DRAFT --> SUBMITTED : submit [plays to an ending]
    SUBMITTED --> DRAFT : withdraw
    SUBMITTED --> APPROVED : approve [reviewer is not the author]
    SUBMITTED --> REJECTED : return [reason given]
    REJECTED --> DRAFT : save
    APPROVED --> DRAFT : save
    APPROVED --> RELEASED : publish
    RELEASED --> [*]
```

### GameRelease

```mermaid
---
title: state machine GameRelease
---
stateDiagram-v2
    [*] --> CURRENT : publish
    CURRENT --> SUPERSEDED : newer release published, another set current
    SUPERSEDED --> CURRENT : set current again, current release recalled
    CURRENT --> RECALLED : recall [an older release exists]
    RECALLED --> [*]
```
