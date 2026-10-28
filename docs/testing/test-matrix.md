# Test traceability matrix (Review 2)

Status of 28/10/2026, BE `origin/main` (branch `docs/review2-diagrams`). Mapping only: no test or product code was changed.
A test is mapped only where its body exercises the behaviour (assertions read, not names). "Partial" always says what is missing.

## 0. Rule and sources

Review 2 guide (`SWP490_R2_Huong-dan-chuan-bi-Review-2_1.pdf`, section 9.3 "Test case chức năng", p. 6), verbatim:

> Số test case tối thiểu tính theo công thức dưới đây và ghi thành con số cố định trong Review 2 Commitment.
>
> | Nguồn | Tối thiểu | Yêu cầu |
> |---|---|---|
> | Mỗi tính năng bắt buộc | 3 | 1 luồng chính; 1 kiểm tra dữ liệu hoặc quy tắc nghiệp vụ; 1 ngoại lệ hoặc phân quyền |
> | Mỗi main workflow | 1 | Test end-to-end theo kịch bản chính |
> | Mỗi chuyển trạng thái của thực thể chính | 1 | Bao gồm cả chuyển trạng thái không hợp lệ bị từ chối |
> | Mỗi NFR đã chốt | 1 | Test đo theo đúng chỉ số và ngưỡng |
>
> Ví dụ: 15 tính năng × 3 + 5 workflow + 12 chuyển trạng thái + 4 NFR = 66 test case

Section 9.4 "Ngưỡng đủ điều kiện ra Viva": "Tỷ lệ test đạt — Ít nhất 90% tổng số; 100% test luồng chính của tính năng bắt buộc";
"Tự động hoá — Ít nhất 50% test case chạy tự động trong CI — Chạy CI trên bản nộp". Section 4 (Design Brief) also requires:
"Mỗi NFR có chỉ số, ngưỡng và cách đo" and "Tiêu chí chấp nhận đạt yêu cầu khi viết được thành test và cho kết quả đạt/không đạt rõ ràng."

| Source | Used for |
|---|---|
| `FA26SE220.docx` (Register, Final v2 14/10/2026) §3.2 c, d, g | 12 FR bullets, 5 NFR bullets, MVP / stretch list |
| `Diagram/ACTIVITY/act-1…act-5` | 5 main workflows |
| `Diagram/STATE-MACHINE/*-simple.drawio` + `transition-tables.md` | 44 legal transitions (figures) + rejected transitions (tables) |
| `src/test/**` (this repo) | 28 test classes, 171 `@Test` methods, 173 executions |
| `.github/workflows/ci.yml` | `mvn -B test` on every `pull_request` and every push to `main`; deploy job `needs: test` |

**Feature IDs.** The registration form gives no FR IDs. The IDs below are assigned here, by actor and bullet order, so they do not
collide with the old `FR-xx` numbers of the withdrawn September register. All 12 bullets are mandatory: §3.2 g lists TP1–TP3, play
intake, reports and quiz (TP4) and the web consoles (TP5) as the MVP; the stretch list is narration, class clustering and the
nightly summary only. The daily summary (part of ADL-3) is therefore stretch and is not required here.

## 1. Summary

Counting: a feature needs 3 cases (main flow, data / business rule, exception / permission). A feature is **covered** when each of
the three kinds has a test and every part of the FR bullet is exercised; **partial** when the three kinds exist but a part of the
bullet has no test. Transitions are the 44 arcs with a trigger in the five `-simple` figures (an arc with several triggers counts
once per trigger; edges into a final state without a trigger are not counted). NFRs are the 5 bullets of §3.2 d.

| Category | Required (min.) | Covered | Partial | Missing | Open items behind partial / missing |
|---|---|---|---|---|---|
| Mandatory features (12 × 3) | 36 | 18 (6 features) | 18 (6 features) | 0 | 8 untested parts of FR bullets |
| Main workflows | 5 | 3 | 2 | 0 | 2 |
| State transitions (legal, figures) | 44 | 34 | 2 | 8 | 10 |
| NFRs | 5 | 0 | 4 | 1 | 11 of 16 criteria not fully tested |
| **Total** | **90** | **55** | **26** | **9** | **31** |
| Rejected transitions (tables, recommended) | 31 | 26 | 1 | 4 | 5 |

One new test often closes several open items; section 7 lists about 11 tests that would close nearly all of them.

Real run (28/10/2026, local, `mvn -B test`, Java 22, `CWEBP` set as in CI): **173 tests, 173 passed, 0 failures, 0 errors,
0 skipped**, build time 6 min 07 s (surefire XML: 28 files, 252 s test time). Every one of them runs in CI (section 6).

## 2. Mandatory features

Kinds: **M** main flow, **R** data / business rule, **E** exception or permission.

### LRN-1 — Sign in with the code (or its QR image) and the PIN; no account — **covered**
| Kind | Tests |
|---|---|
| M | `GroupSlotFlowTest#openSlotThenChildLogsInWithCodeAndPin` (8-character code, token 8 h, profile returned) |
| R | `AuthFlowTest#treDungCodePinThiRaTokenSlotSaiPinNamLanThiKhoa` (5 wrong PINs → 3008 even with the right PIN); `SlotLifecycleFlowTest#returnSlotArchivesKeepsCodeDeadAndFreesCapacity` (3009); `SlotLifecycleFlowTest#wipeSlotRemovesIdentityButKeepsRow` (3007) |
| E | `AuthFlowTest#childTokenCannotCallAdultApi`; `PlatformGuardsTest#learnerGateChecksTokenTypeNotScope` |

The QR image is rendered by the FE from the same code; it has no test.

### LRN-2 — Play the chapters in order and the mini-games; every choice effect computed on the server — **covered**
| Kind | Tests |
|---|---|
| M | `PlayFlowTest#validBatchStampsChapterAndScores`; `PlayFlowTest#replayAfterAllChaptersStartsNewAttempt` |
| R | `PlayFlowTest#gradedMiniGameScoresEveryQuestionFromInputs`; `PlayFlowTest#careerMatchScoresJobsAndQuestions`; `PlayFlowTest#resendSameBatchIsNoop`; `MiniGameGraderTest` (4 methods, shared cases with the FE rules); `PlayFlowTest#responsesHideFiveStats` |
| E | `PlayFlowTest#wrongBatchGetsRightErrors` (7003 out of order, 7001, 7002); `PlayFlowTest#adultTokenAndReturnedSlotRejected` |

### LRN-3 — Take a class quiz (students only); one dashboard: chapter to continue, quizzes, achievements — **partial**
| Kind | Tests |
|---|---|
| M | `QuizFlowTest#teacherPublishesAndLearnersSubmit`; `PlayFlowTest#stateAndDashboardReflectRun`; `LearnerDashboardFlowTest#familyDashboardShowsLearnerAndEmptyBlocks`; `LearnerDashboardFlowTest#classDashboardHasEmptyQuizLists` |
| R | `QuizFlowTest#validationAndErrorCodes` (8006, 8005, 8004); answer key never sent (asserted in `teacherPublishesAndLearnersSubmit`) |
| E | `LearnerDashboardFlowTest#accountTokenIsRejected`, `#missingTokenGets401`, `#returnedSlotGets3009`, `#wipedSlotGets3007` |

Missing: achievements. Dashboard tests only assert `achievements` is empty, and `PlatformGuardsTest#skeletonEndpointIs501` asserts
`GET /api/achievements` answers 501 (not implemented). Needs implementation before a test can pass.

### ADL-1 — Register with e-mail or Google (one-time code, password reset); buy or renew a plan by VietQR; payment history — **partial**
| Kind | Tests |
|---|---|
| M | `AuthFlowTest#verifiedUserLogsInAndTokenWorksElsewhere`; `#googleLoginCreatesAccountThenReusesIt`; `#resetPasswordInvalidatesOldPassword`; `PaymentServiceImplTest#paidWebhookGrantsPlanFromToday` (unit, PayOS mocked) |
| R | `AuthFlowTest#duplicateEmailWithDifferentCaseIsRejected`; `#fiveWrongOtpsKillTheCode`; `#resendKillsOldCodeAndNewCodeWorks`; `#registeringAgainOnUnverifiedEmailReplacesPassword`; `PaymentServiceImplTest#earlyRenewalStartsAfterCurrentExpiry`; `#buyingAgainReturnsTheOpenOrderWithoutCallingPayOS`; `#monthsComeFromTheOrderNotTheCurrentPlan`; `EntitlementFlowTest#listsExpiredAndActiveNewestFirst` |
| E | `AuthFlowTest#cannotLoginBeforeEmailVerified`; `#fiveWrongPasswordsLockTheAccount`; `#googleLoginWithPasswordAccountEmailReturns3020`; `#googleLoginWithBadTokenOrUnverifiedEmailReturns3002`; `#sixthMailRequestFromSameIpIsRateLimited`; `PaymentServiceImplTest#adminCannotBuyAPlan` |

Missing: (1) payment history (`GET /api/payments` list) has no test; `AuthFlowTest` only hits `GET /api/payments/123` for a 4005;
(2) buying is tested only as a unit with PayOS mocked; no HTTP test of `POST /api/payments` creating a PENDING order.
Other `AuthFlowTest` methods (change password, `/me`, link Google, concurrent Google sign-in, rate limit behind the tunnel) add
depth to the same FR.

### ADL-2 — Open a group and its seats, hand out codes; return, re-PIN or erase a seat; end the group — **covered**
| Kind | Tests |
|---|---|
| M | `GroupSlotFlowTest#parentOpensFamilyGroup`; `#openSlotThenChildLogsInWithCodeAndPin`; `SlotLifecycleFlowTest#returnSlotArchivesKeepsCodeDeadAndFreesCapacity`; `#changePinReplacesPinAndClearsLock`; `#wipeSlotRemovesIdentityButKeepsRow`; `GroupCloseBulkFlowTest#closeGroupArchivesSlotsAndFreesContext`; `SlotLifecycleFlowTest#getMyGroupsShowsSlotUsedFromActiveSlots` |
| R | `GroupSlotFlowTest#fifthSlotIsRejected`; `#secondOpenFamilyGroupIsRejected`; `#withoutPlanCannotOpenGroup`; `#groupInputValidationRejectsBlankNameAndBadContext`; `#slotInputValidationRejectsBadPin`; `GroupCloseBulkFlowTest#expiredPlanCannotOpenNewSlot`; `#expiredPlanCanStillReturnChangePinAndWipe`; `#closedGroupHasZeroCapacity`; `#archivedSlotCanBeWiped` |
| E | `GroupSlotFlowTest#anotherParentCannotOpenSlotInMyGroup`; `SlotLifecycleFlowTest#strangerCannotReturnOrWipeOrChangePinMySlot`; `#slotsAndCapacityAreOwnerOnly`; `#readUnknownGroupReturns404`; `GroupCloseBulkFlowTest#closeGroupIsOwnerOnly` |

### ADL-3 — Read each learner's progress, five indicators, concept mastery, achievements and daily summary — **partial**
| Kind | Tests |
|---|---|
| M | `ReportFlowTest#ownerReadsSlotReport` (progress + 5 indicators); `ReportFlowTest#closingGroupFreezesNumbers` (group report) |
| R | `ReportFlowTest#closingGroupFreezesNumbers` (frozen on close, `report-v1`); `#wipedSlotHidesIdentityButKeepsFigures`; `#classGroupHasEmptyQuizzesObject`; `QuizFlowTest#classResultsAndReports` (quiz block of the reports) |
| E | `ReportFlowTest#foreignAdultAndSlotTokenRejected`; `#missingSlotAndGroupGetRightErrors` |

Missing: concept mastery and achievements (tests only assert `concepts` / `achievements` are empty). Daily summary is stretch
(asserted `null`), not required.

### TCH-1 — Confirm parental consent before the first seat; up to forty seats, singly or from a pasted list — **partial**
| Kind | Tests |
|---|---|
| M | `GroupSlotFlowTest#classGroupNeedsConsentBeforeOpeningSlot`; `GroupCloseBulkFlowTest#bulkOpensWholeLotInOneCall` (FAMILY group, 4 seats) |
| R | `GroupCloseBulkFlowTest#classGroupBulkNeedsConsent` (5003); `#bulkOverLimitRejectsWholeLot`; `#bulkRejectsMixedGroupAndEmptyBody` |
| E | consent by a stranger → 3004 (in `classGroupNeedsConsentBeforeOpeningSlot`); `GroupCloseBulkFlowTest#bulkInSomeoneElsesGroupIsForbidden` |

Missing: the 40-seat cap. Only `slotLimit = 40` is read back; no test opens 40 seats in a CLASS group or rejects the 41st, and no
bulk open succeeds in a CLASS group.

### TCH-2 — Assemble a quiz from the pool, set a deadline, publish; read per-student and class results — **covered**
| Kind | Tests |
|---|---|
| M | `QuizFlowTest#teacherPublishesAndLearnersSubmit`; `#classResultsAndReports`; `#postTestAutoCreatesAndDashboardReflectsIt` |
| R | `QuizFlowTest#validationAndErrorCodes` (8007, past `dueAt` 1001, 8002); `#importQuestionsUpsertsAndHidesChapterSet`; `StudioQuestionFlowTest#questionInPublishedQuizKeepsContentButLabelsChange` |
| E | `QuizFlowTest#permissionGates` (8008, 3004, 3005); `#postTestFlagPermissions` |

### ADM-1 — Set plan prices and durations; grant a plan or internal role with a recorded reason; inspect and reconcile payment transactions, including those held for review — **partial**
| Kind | Tests |
|---|---|
| M | `AdminPlanFlowTest#adminSetsPriceAndAnyoneCanRead`; `EntitlementFlowTest#adminGrantsPlanAndOwnerSeesItImmediately`; `AdminRoleFlowTest#grantThenRevokeChangesMeAndWritesLog` (reason in the log) |
| R | `AdminPlanFlowTest#priceAndMonthsHaveCeilings`; `#nonPositivePriceIsRejected`; `EntitlementFlowTest#grantRenewsFromDayAfterPreviousExpiry`; `#grantStartsTodayWhenPreviousPlanExpired`; `#grantRejectsBadInput` (blank reason 1001); `AdminRoleFlowTest#rejectsBadInputAndSelfAdminRevoke`; `#roleLogShowsWhoDidWhatNewestFirst`; `InternalLoginFlowTest#internalAndCustomerAccountsStaySeparate` |
| E | `AdminPlanFlowTest#regularUserCannotSetPrice`; `EntitlementFlowTest#nonAdminCannotGrant`; `AdminRoleFlowTest#nonAdminIsForbidden` |

Missing: transactions. `AdminPlanFlowTest#regularUserCannotSetPrice` only checks that `GET /api/admin/transactions` returns an
array; reconcile and the needs-review list are not exercised over HTTP (`needsReview` is set in a unit test only).

### CNT-1 — Editor: draft a chapter on a form, propose the class of the change, submit — **covered**
| Kind | Tests |
|---|---|
| M | `StudioDraftReviewFlowTest#editorSubmitsReviewerReturnsThenApproves`; `ContentFlowTest#draftContentRoundTripAndReleasePreview` |
| R | `ContentFlowTest#draftPublishReadFreezeRollback` (bad draft 1001); `ContentSchemaTest` (16 methods, 18 runs, e.g. `#clearErrorsAreRejectedWithFieldPath`, `#brokenFlowWarnsOnSaveAndBlocksOnSubmit`, `#miniGameConfigIsCheckedAgainstItsKind`) |
| E | `StudioDraftReviewFlowTest#selfReviewAndWrongRolesAreRefused` (submit without class 1001; reviewer / manager cannot edit 3004) |

"On a form" is FE (Studio); FE tests exist but are not in CI (section 6).

### CNT-2 — Reviewer: play the draft, confirm the class, pass or return it; never a draft of their own — **covered**
| Kind | Tests |
|---|---|
| M | `StudioDraftReviewFlowTest#editorSubmitsReviewerReturnsThenApproves`; `ContentFlowTest#draftContentRoundTripAndReleasePreview` (draft content served with real asset URLs for play) |
| R | return without reason → 1001 and class confirmed upward (MINOR), both in `editorSubmitsReviewerReturnsThenApproves` |
| E | `StudioDraftReviewFlowTest#selfReviewAndWrongRolesAreRefused` (author 6002 even as ADMIN; editor / manager cannot review 3004) |

### CNT-3 — Manager: gather passed drafts into a release and roll back a faulty one; until then the administrator publishes alone — **partial**
| Kind | Tests |
|---|---|
| M | `ContentFlowTest#draftPublishReadFreezeRollback`; `#recallRollsBackAndNewChapterIsMajor` |
| R | `ContentVersionTest#comparesNumericallyNotAsText`, `#bumpsFromHighestEverReleased`; `ContentFlowTest#draftContentRoundTripAndReleasePreview` (`/releases/next`); 6004 / 6005 / 6006 in `draftPublishReadFreezeRollback` |
| E | 6007 publisher wrote the draft (`draftPublishReadFreezeRollback`); editor / reviewer cannot publish or set current, 3004 (`selfReviewAndWrongRolesAreRefused`) |

Missing: a successful publish or rollback by a `GAME_MANAGER` account. Every successful publish runs as ADMIN; the manager is only
seen reaching a 409. Approval in `ContentFlowTest` is written straight to the repository (`ContentTestSupport.approve`), not
through the review API.

## 3. Main workflows

| WF | Activity diagram | Tests (end to end over HTTP) | Status |
|---|---|---|---|
| WF-1 Payment | `act-1-payment` | `PaymentServiceImplTest#paidWebhookGrantsPlanFromToday`, `#paidAfterFailedStillGrantsWithNote`, `#adminCannotBuyAPlan` (unit, mocks); `AuthFlowTest#buyingPlanAfterLoginUpdatesScopeWithoutLogout` (plan inserted by repository) | **partial**: no single test goes buy → PENDING → webhook → PAID → plan usable; no cancel / expiry branch |
| WF-2 Group and slot | `act-2-group-slot` | `GroupSlotFlowTest#classGroupNeedsConsentBeforeOpeningSlot` (class branch), `#openSlotThenChildLogsInWithCodeAndPin` (family branch to play token); lock branch `AuthFlowTest#treDungCodePinThiRaTokenSlotSaiPinNamLanThiKhoa`; refusal branch `GroupSlotFlowTest#fifthSlotIsRejected` | covered |
| WF-3 Play | `act-3-play` | `PlayFlowTest#replayAfterAllChaptersStartsNewAttempt`, `#stateAndDashboardReflectRun`; invalid branch `#wrongBatchGetsRightErrors`; already-saved branch `#resendSameBatchIsNoop` | covered (BE half; the FE load / feedback lanes have no CI test) |
| WF-4 Quiz | `act-4-quiz` | `QuizFlowTest#teacherPublishesAndLearnersSubmit` + `#classResultsAndReports` (class test); `#postTestAutoCreatesAndDashboardReflectsIt` (post-test); refusal `#validationAndErrorCodes` | covered |
| WF-5 Content | `act-5-content` | `StudioDraftReviewFlowTest#editorSubmitsReviewerReturnsThenApproves` (save → submit → return → approve); `ContentFlowTest#recallRollsBackAndNewChapterIsMajor` (publish → recall) | **partial**: the two halves are separate and publish uses a repository approval; no one test runs editor → reviewer → manager publish → recall |

Note: the register (§3.2 g, TP6) promises "end-to-end scenarios for the four main flows"; the diagrams define five.

## 4. State transitions

44 legal transitions from the `-simple` figures. "Rejected" rows come from `transition-tables.md`; the guide asks that invalid
transitions be included, so they are listed in 4.7 and should be counted in the Commitment if the team agrees.

### 4.1 Transaction
| # | From → To | Trigger | Tests | Status |
|---|---|---|---|---|
| T1 | ● → PENDING | buy | `PaymentServiceImplTest#buyingAgainReturnsTheOpenOrderWithoutCallingPayOS` (reuse of an open PENDING order only) | partial: creating a new PENDING order via PayOS is never exercised |
| T2 | PENDING → PAID | paymentConfirmed (webhook) | `PaymentServiceImplTest#paidWebhookGrantsPlanFromToday` | covered (webhook path; reconcile path untested) |
| T3 | PENDING → FAILED | cancel | — | missing |
| T4 | PENDING → FAILED | linkExpired (reconcile) | — | missing |
| T5 | PENDING → FAILED | paymentFailed (reconcile) | — | missing |
| T6 | FAILED → PAID | paymentConfirmed (late) | `PaymentServiceImplTest#paidAfterFailedStillGrantsWithNote` | covered |

### 4.2 LearnerSlot
| # | From → To | Trigger | Tests | Status |
|---|---|---|---|---|
| S1 | ● → ACTIVE.UNLOCKED | openSlot | `GroupSlotFlowTest#openSlotThenChildLogsInWithCodeAndPin`; `GroupCloseBulkFlowTest#bulkOpensWholeLotInOneCall` | covered |
| S2 | UNLOCKED → LOCKED | wrongPin [5th in a row] | `AuthFlowTest#treDungCodePinThiRaTokenSlotSaiPinNamLanThiKhoa`; `SlotLifecycleFlowTest#changePinReplacesPinAndClearsLock` | covered |
| S3 | LOCKED → UNLOCKED | after (15 min) | — (every test uses a frozen clock) | missing |
| S4 | LOCKED → UNLOCKED | changePin | `SlotLifecycleFlowTest#changePinReplacesPinAndClearsLock` | covered |
| S5 | ACTIVE → ARCHIVED | returnSlot | `SlotLifecycleFlowTest#returnSlotArchivesKeepsCodeDeadAndFreesCapacity` | covered |
| S6 | ACTIVE → ARCHIVED | closeGroup | `GroupCloseBulkFlowTest#closeGroupArchivesSlotsAndFreesContext` | covered |
| S7 | ACTIVE → WIPED | wipeSlot | `SlotLifecycleFlowTest#wipeSlotRemovesIdentityButKeepsRow` | covered |
| S8 | ARCHIVED → WIPED | wipeSlot | `GroupCloseBulkFlowTest#archivedSlotCanBeWiped` | covered |

### 4.3 RunState
| # | From → To | Trigger | Tests | Status |
|---|---|---|---|---|
| R1 | NOT_STARTED → IN_PROGRESS | submitChapter [chapter 1] | `PlayFlowTest#validBatchStampsChapterAndScores`; `#stateAndDashboardReflectRun` | covered |
| R2 | IN_PROGRESS → IN_PROGRESS | submitChapter [next chapter] | `PlayFlowTest#replayAfterAllChaptersStartsNewAttempt` (chapter 2) | covered |
| R3 | IN_PROGRESS → COMPLETED | when(all chapters of current release done) | `PlayFlowTest#replayAfterAllChaptersStartsNewAttempt` (`continueChapter` null after the last chapter) | covered |
| R4 | COMPLETED → IN_PROGRESS | when(current release has more chapters) | — | missing |
| R5 | COMPLETED → IN_PROGRESS | submitChapter [chapter 1, new attempt] | `PlayFlowTest#replayAfterAllChaptersStartsNewAttempt` | covered |
| R6 | PLAYABLE → ◉ | when(slot archived or wiped) | `PlayFlowTest#adultTokenAndReturnedSlotRejected` (archived, 3009) | covered (wiped case only on the dashboard) |

### 4.4 Quiz
| # | From → To | Trigger | Tests | Status |
|---|---|---|---|---|
| Q1 | ● → DRAFT | [teacher creates a class test] | `QuizFlowTest#teacherPublishesAndLearnersSubmit` (helper asserts `publishedAt` null) | covered |
| Q2 | DRAFT → DRAFT | edit | — (PUT is only tested on a published quiz, 8002) | missing |
| Q3 | DRAFT → ◉ | delete | — (DELETE is only tested on a published quiz, 8002) | missing |
| Q4 | DRAFT → PUBLISHED | publish | `QuizFlowTest#teacherPublishesAndLearnersSubmit` | covered |
| Q5 | ● → PUBLISHED | [child finished a chapter, post-test on, none yet] | `QuizFlowTest#postTestAutoCreatesAndDashboardReflectsIt` | covered |
| Q6 | OPEN → HIDDEN | postTestOff [post-test] | `QuizFlowTest#postTestAutoCreatesAndDashboardReflectsIt` (switch off) | partial: the only post-test was already submitted, so hiding an open one is not observed |
| Q7 | HIDDEN → OPEN | postTestOn | — | missing |
| Q8 | OPEN → OPEN | submit [once per child] | `QuizFlowTest#teacherPublishesAndLearnersSubmit`; `#validationAndErrorCodes` | covered |
| Q9 | PUBLISHED → CLOSED | when(due date passed) | `QuizFlowTest#validationAndErrorCodes` (quiz seeded overdue, submit 8004) | covered |

### 4.5 ContentChange
| # | From → To | Trigger | Tests | Status |
|---|---|---|---|---|
| C1 | ● → DRAFT | save | `ContentFlowTest#draftPublishReadFreezeRollback`; `StudioDraftReviewFlowTest#editorSubmitsReviewerReturnsThenApproves` | covered |
| C2 | DRAFT → DRAFT | save | `ContentFlowTest#draftContentRoundTripAndReleasePreview` (revision 2) | covered |
| C3 | DRAFT → SUBMITTED | submit [plays to an ending] | `StudioDraftReviewFlowTest#editorSubmitsReviewerReturnsThenApproves`; guard: `ContentSchemaTest#brokenFlowWarnsOnSaveAndBlocksOnSubmit` (unit) | covered |
| C4 | SUBMITTED → DRAFT | withdraw | `StudioDraftReviewFlowTest#editorSubmitsReviewerReturnsThenApproves` | covered |
| C5 | SUBMITTED → APPROVED | approve [reviewer is not the author] | same | covered |
| C6 | SUBMITTED → REJECTED | return [reason given] | same | covered |
| C7 | REJECTED → DRAFT | save | same ("Ban 2" after the return) | covered |
| C8 | APPROVED → DRAFT | save | same ("Ban 3", status back to DRAFT) | covered |
| C9 | APPROVED → RELEASED | publish | `ContentFlowTest#draftPublishReadFreezeRollback` (`RELEASED@1`) | covered (approval written by repository) |

### 4.6 GameRelease
| # | From → To | Trigger | Tests | Status |
|---|---|---|---|---|
| G1 | ● → CURRENT | publish | `ContentFlowTest#draftPublishReadFreezeRollback` | covered |
| G2 | CURRENT → SUPERSEDED | newer release published | same (1.0.1 published, 1.0.0 still served) | covered |
| G3 | CURRENT → SUPERSEDED | another set current | same (`POST /releases/1.0.0/current` supersedes 1.0.1) | covered |
| G4 | SUPERSEDED → CURRENT | set current again | same | covered |
| G5 | SUPERSEDED → CURRENT | current release recalled | `ContentFlowTest#recallRollsBackAndNewChapterIsMajor` | covered |
| G6 | CURRENT → RECALLED | recall [an older release exists] | same | covered |

### 4.7 Rejected transitions (from `transition-tables.md`)
| Entity | Request rejected | Tests | Status |
|---|---|---|---|
| Transaction | cancel when not PENDING → 4006 | — | missing |
| Transaction | webhook with bad signature → ignored | — (signature verification is mocked) | missing |
| Transaction | webhook for unknown order → ignored | `PaymentServiceImplTest#unknownOrderCodeIsIgnored` | covered |
| Transaction | paid webhook, amount ≠ order → needsReview, no grant | `PaymentServiceImplTest#amountMismatchGrantsNothingButFlagsForReview` | covered |
| Transaction | repeated paid webhook on PAID → no second grant | `PaymentServiceImplTest#duplicateWebhookGrantsOnce` | covered |
| Transaction | buy by an internal account → 3025 | `PaymentServiceImplTest#adminCannotBuyAPlan` | covered |
| LearnerSlot | open over quota 3006 / no consent 5003 / no plan 3005 | `GroupSlotFlowTest#fifthSlotIsRejected`, `#classGroupNeedsConsentBeforeOpeningSlot`; `GroupCloseBulkFlowTest#expiredPlanCannotOpenNewSlot` | covered |
| LearnerSlot | login while LOCKED → 3008 | `AuthFlowTest#treDungCodePinThiRaTokenSlotSaiPinNamLanThiKhoa` | covered |
| LearnerSlot | login on ARCHIVED → 3009 | `SlotLifecycleFlowTest#returnSlotArchivesKeepsCodeDeadAndFreesCapacity` | covered |
| LearnerSlot | login on WIPED → 3007 | `SlotLifecycleFlowTest#wipeSlotRemovesIdentityButKeepsRow` | covered |
| LearnerSlot | return / changePin when not ACTIVE → 5004 | `SlotLifecycleFlowTest#returnSlotArchivesKeepsCodeDeadAndFreesCapacity`, `#changePinReplacesPinAndClearsLock` | covered |
| LearnerSlot | wipe when WIPED → 5004 | `SlotLifecycleFlowTest#wipeSlotRemovesIdentityButKeepsRow`; `GroupCloseBulkFlowTest#archivedSlotCanBeWiped` | covered |
| LearnerSlot | close a closed group → 5002 | `GroupCloseBulkFlowTest#closeGroupArchivesSlotsAndFreesContext` | covered |
| Quiz | update / delete when PUBLISHED → 8002 | `QuizFlowTest#validationAndErrorCodes`, `#postTestAutoCreatesAndDashboardReflectsIt` | covered |
| Quiz | submit when CLOSED → 8004 | `QuizFlowTest#validationAndErrorCodes` | covered |
| Quiz | second submit → 8005 | `QuizFlowTest#validationAndErrorCodes` | covered |
| Quiz | submit when not visible (hidden post-test, other class) → 8001 | — | missing |
| Quiz | create for a FAMILY group → 8008 | `QuizFlowTest#permissionGates` | covered |
| RunState | chapter out of order → 7003 | `PlayFlowTest#wrongBatchGetsRightErrors` | covered |
| RunState | unknown release 7001 / unknown option 7002 | `PlayFlowTest#wrongBatchGetsRightErrors` | covered |
| RunState | more than 100 rows → 2003; two rows for one choice group → 1001 | — | missing |
| RunState | resend of a saved chapter → no change | `PlayFlowTest#resendSameBatchIsNoop` | covered |
| ContentChange | save while SUBMITTED → 6003; submit when not DRAFT → 6003 | `StudioDraftReviewFlowTest#editorSubmitsReviewerReturnsThenApproves` | covered |
| ContentChange | submit with an incomplete flow → 1001 | `ContentSchemaTest#brokenFlowWarnsOnSaveAndBlocksOnSubmit` (unit, no HTTP) | partial |
| ContentChange | withdraw by someone else → 3004 | `StudioDraftReviewFlowTest#selfReviewAndWrongRolesAreRefused` | covered |
| ContentChange | approve / return by the author → 6002 | same | covered |
| ContentChange | publish by the author → 6007 | `ContentFlowTest#draftPublishReadFreezeRollback` | covered |
| GameRelease | publish with no approved draft → 6005; version sent differs → 6004 | `ContentFlowTest#draftPublishReadFreezeRollback` | covered |
| GameRelease | publish while current is not newest → 6006 | same | covered |
| GameRelease | set a recalled release current → 6006 | `ContentFlowTest#recallRollsBackAndNewChapterIsMajor` | covered |
| GameRelease | recall without reason 1001 / not current or nothing to fall back to 6006 | same | covered |

Count: 31 rows; 26 covered, 1 partial, 4 missing.

## 5. Non-functional requirements

Wording is quoted from §3.2 d. Each bullet holds several criteria; a bullet is covered only when every criterion has a test.

| NFR | Criterion as written | Measurable? | Tests | Status |
|---|---|---|---|---|
| NFR-1 Performance and compatibility | "a chapter loads within 5 seconds on a simulated 4G link" | yes (metric + threshold); method not written (which throttling profile, cold or warm cache, which chapter) | — (one manual measurement on 09/10, not in the repo) | missing |
| | "API responses within 500 ms at the 95th percentile with 50 concurrent learners" | yes; endpoint set, duration and tool not written | — (no load test) | missing |
| | "desktop browsers at 1024 px or wider, current Android for the adult application" | partly: browser list and Android version are not stated ("current" is not a threshold) | — | missing |
| NFR-2 Reliability | "a loaded chapter completes without the server" | yes (pass / fail) | — (FE behaviour; no CI test) | missing |
| | "the end-of-chapter batch is resent until acknowledged" | partly: no retry interval, limit or persistence across reload | BE side only: `PlayFlowTest#resendSameBatchIsNoop` (a resend is accepted without duplicates) | partial |
| | "automatic deployment after the tests pass, with a dump before and rollback on failure" | yes, by inspection / drill | `ci.yml` (`deploy` needs `test`); dump and rollback live in `/opt/finteen-ci-deploy.sh` outside the repo, no test | partial (config only) |
| | "nightly dumps kept fourteen days" | yes, by ops check | — (cron on the server) | missing (manual check) |
| NFR-3 Privacy and access control | "a minor is stored as a display name and a group only" | yes | `GroupSlotFlowTest#openSlotThenChildLogsInWithCodeAndPin` (PIN never returned); `ReportFlowTest#ownerReadsSlotReport` (no `pinHash` / e-mail); `#wipedSlotHidesIdentityButKeepsFigures` | covered |
| | "learner data require a token and ownership of the group" | yes | `ReportFlowTest#foreignAdultAndSlotTokenRejected`; `SlotLifecycleFlowTest#slotsAndCapacityAreOwnerOnly`; `QuizFlowTest#permissionGates`; `LearnerDashboardFlowTest#missingTokenGets401`; `EntitlementFlowTest#neverShowsAnotherAccountsPlan` | covered |
| | "passwords, PINs and codes are salted hashes" | yes, but **conflicts with the code**: the seat `code` is stored in clear (`LearnerSlot.code`, returned to the owner in slot lists and reports); only PIN, password and OTP are hashed | indirect only (`AuthFlowTest` comments note BCrypt; no assertion on stored hashes) | missing |
| | "five wrong PINs lock a seat for fifteen minutes" | yes | lock: `AuthFlowTest#treDungCodePinThiRaTokenSlotSaiPinNamLanThiKhoa`, `SlotLifecycleFlowTest#changePinReplacesPinAndClearsLock`; 15-minute expiry: — | partial |
| NFR-4 Measurement integrity | "a batch carries the release version, seat, chapter and attempt index, so a resend is recognised" | yes | `PlayFlowTest#resendSameBatchIsNoop`; `#replayAfterAllChaptersStartsNewAttempt` | covered |
| | "the server recomputes the indicators from the frozen release and ignores client totals" | yes | recompute: `PlayFlowTest#validBatchStampsChapterAndScores` (stats from the release JSON, unknown key `FIQ` ignored); client totals: not exercised (the request DTO has no stats field, so nothing is sent) | partial |
| NFR-5 Payment integrity | "one grant per order" / "a repeated delivery grants nothing" | yes | `PaymentServiceImplTest#duplicateWebhookGrantsOnce` | covered |
| | "the gateway signature is verified" | yes | — (`webhooks.verify` is mocked to succeed in every test) | missing |
| | "an amount mismatch is held for the administrator" | yes | `PaymentServiceImplTest#amountMismatchGrantsNothingButFlagsForReview` | covered |

Per bullet: NFR-1 missing; NFR-2, NFR-3, NFR-4, NFR-5 partial.

## 6. Totals and automation

| Item | Value |
|---|---|
| BE test classes | 28 (plus helper `ContentTestSupport`) |
| BE `@Test` methods | 171 (173 executions; `ContentSchemaTest` has a parameterised method) |
| Integration (Spring Boot + MockMvc + H2) | 21 classes; plain unit: 7 classes (`PaymentServiceImplTest`, `ContentSchemaTest`, `ContentVersionTest`, `MiniGameGraderTest`, `AssetImageTest`, `UniqueViolationTest`, `FirebaseIdTokenDecoderTest`) |
| Local run 28/10/2026 | 173 run, 173 pass, 0 fail, 0 error, 0 skipped (100 %) |
| CI | `.github/workflows/ci.yml`: `mvn -B test` (Java 17 Temurin, `cwebp` 1.6.0) on every pull request and every push to `main`; production deploy runs only after the test job passes |
| BE tests run automatically in CI | 173 / 173 = 100 % |
| Mapped cases in sections 2–5 that are automated in CI | all of them (no manual case is mapped yet) |
| FE repo (`dataiyeumenh/fa26se220-finteen-fe`, `origin/main` 7580ffa) | 29 `tests/*.test.mjs` files, about 175 `test(` / `it(` cases on `node --test`; package scripts run only subsets (`test:workspace`, `test:internal`, `test:auth`); **no `.github/workflows`**, so 0 % in CI. Not run in this pass. |

When manual cases are added for NFR-1 (load time, load test) and NFR-2 (backup retention, rollback drill), the CI share stays far
above the 50 % threshold as long as the BE suite is counted.

## 7. Gaps, ordered by required cases they would close

| # | Test to add | Closes |
|---|---|---|
| 1 | Payment flow over HTTP with PayOS stubbed: buy → PENDING; webhook → PAID → plan usable; cancel → FAILED; reconcile EXPIRED / CANCELLED → FAILED; cancel non-PENDING 4006; bad signature ignored; `GET /api/payments` history; admin transactions + needs-review list | T1, T3, T4, T5, WF-1, ADL-1, ADM-1, NFR-5, 2 rejected rows (9) |
| 2 | Quiz draft life: edit a DRAFT, delete a DRAFT; post-test off while one is open (hidden, 8001) then on again (visible) | Q2, Q3, Q6, Q7, rejected 8001 (5) |
| 3 | One content E2E through the API with real roles: editor saves + submits, reviewer approves, `GAME_MANAGER` publishes, then recalls | WF-5, CNT-3 (2) |
| 4 | Slot lock expiry with a movable clock (lock at T, still 3008 at T+14 min, login at T+15 min) | S3, NFR-3 lock (2) |
| 5 | NFR-1 measurements: Lighthouse / DevTools "Fast 4G" load of one chapter ≤ 5 s; k6 or JMeter 50 virtual users, p95 ≤ 500 ms on named endpoints | NFR-1 (1 NFR, 2–3 criteria; can be manual or a separate CI job) |
| 6 | RunState reopens: learner COMPLETED on a 1-chapter release, a 2-chapter release becomes current, state is IN_PROGRESS with `continueChapter = 2` | R4 (1) |
| 7 | CLASS group: bulk open 40 seats after consent, 41st rejected 3006 | TCH-1 (1) |
| 8 | Batch with extra `stats` / totals in the body → stored indicators unchanged; batch > 100 rows → 2003; duplicate choice group → 1001 | NFR-4, 1 rejected row (2) |
| 9 | Stored-secret check: password, PIN and OTP columns hold BCrypt hashes (and decide the wording for seat codes) | NFR-3 hashing (1) |
| 10 | Achievements and concept mastery: needs implementation first (`/api/achievements` is 501) | LRN-3, ADL-3 (2) |
| 11 | Ops evidence for NFR-2: rollback drill log, backup listing showing 14 files; FE offline-completion test | NFR-2 (manual) |

## 8. Wording problems found

1. The register has no FR IDs and several bullets bundle features (ADL-1: account, plan purchase, payment history; ADM-1: prices,
   grants, transactions; LRN-3: quiz and dashboard). The Design Brief should split and number them, otherwise "3 cases per
   feature" has no fixed count.
2. NFR-3 says "codes are salted hashes"; seat codes are stored and shown in clear. Either reword to "one-time codes" or change the
   implementation.
3. NFR-1 has thresholds but no measurement method; "current Android" and "desktop browsers" have no version list. The guide asks
   for metric, threshold and method in at most 6 lines.
4. NFR-2 "resent until acknowledged" has no interval or limit; "nightly dumps kept fourteen days" and "rollback on failure" can
   only be verified by inspection or a drill, not by a CI test.
5. ADL-3 lists "concept mastery" and "daily summary"; §3.2 g puts the nightly summary in the stretch list and does not name
   knowledge tracing in the MVP, so whether concept mastery is mandatory is unclear. Achievements are in the FRs (LRN-3, ADL-3)
   but not implemented.
6. TP6 promises end-to-end scenarios for "the four main flows"; the activity diagrams define five.
7. One test method name is not English (`AuthFlowTest#treDungCodePinThiRaTokenSlotSaiPinNamLanThiKhoa`).
