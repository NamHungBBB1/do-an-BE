# New tests for Review 2 (branch `test/review2-gaps`, 28/10/2026)

IDs refer to `docs/testing/test-matrix.md` (branch `docs/review2-diagrams`). Every test runs over HTTP (MockMvc + H2) and in CI
(`mvn -B test`). No product code was changed.

## PaymentFlowTest (new class)

PayOS is faked: a real `PayOS` client with a test checksum key whose `paymentRequests()` (create / get / cancel, the network
calls) returns a Mockito mock. `webhooks().verify()` is the SDK's real signature check. The real PayOS is never called.

| Test | Covers |
|---|---|
| `buyThenSignedWebhookPaysGrantsPlanAndSendsReceipt` | WF-1, T1, T2, ADL-1 (buy over HTTP), NFR-5 (one grant per order, repeated delivery grants nothing) |
| `webhookWithBadSignatureIsIgnored` | NFR-5 "the gateway signature is verified"; rejected row "webhook with bad signature → ignored" |
| `cancelPendingOrderFailsItAndSecondCancelIs4006` | T3; rejected row "cancel when not PENDING → 4006" |
| `expiredOrFailedLinkTurnsOrderFailed` | T4 (EXPIRED on buyer poll), T5 (FAILED / CANCELLED on admin reconcile), ADM-1 (reconcile) |
| `adminReconcileOfPaidLinkGrantsPlan` | T2 via the reconcile path, ADM-1 |
| `amountMismatchIsHeldForReviewInAdminList` | ADM-1 (transaction list, held-for-review row), NFR-5 amount mismatch over HTTP |
| `paymentHistoryListsOnlyMyOrdersNewestFirst` | ADL-1 payment history (`GET /api/payments`), T1 reuse of an open order |
| `gatewayErrorOnCreateLeavesNoOrder` | ADL-1 exception (4004, PENDING row rolled back) |

## QuizFlowTest (3 new)

| Test | Covers |
|---|---|
| `draftQuizCanBeEditedThenDeleted` | Q2 (DRAFT → DRAFT edit), Q3 (DRAFT → deleted), 8001 on an unpublished quiz |
| `postTestHiddenWhenSwitchedOffAndOpenAgainWhenOn` | Q6 (OPEN → HIDDEN, unsubmitted post-test), Q7 (HIDDEN → OPEN), rejected row 8001 (hidden post-test, chapter not done) |
| `studentCannotSubmitQuizOfAnotherClass` | rejected row 8001 (other class) |

## ContentFlowTest (1 new)

| Test | Covers |
|---|---|
| `editorReviewerManagerPublishThenRecall` | WF-5, CNT-3 (publish and recall by a real `GAME_MANAGER`, approval through the review API), C9, G5, G6 |

## AuthFlowTest (1 new, 1 renamed)

| Test | Covers |
|---|---|
| `lockedSlotUnlocksAfterFifteenMinutes` | S3 (LOCKED → UNLOCKED after 15 min, movable test clock), NFR-3 "lock for fifteen minutes" |
| `childLogsInWithCodeAndPinAndFiveWrongPinsLockTheSlot` | renamed from `treDungCodePinThiRaTokenSlotSaiPinNamLanThiKhoa` (wording problem 7); same body |

## PlayFlowTest (3 new)

| Test | Covers |
|---|---|
| `finishedRunReopensWhenCurrentReleaseHasMoreChapters` | R4 (COMPLETED → IN_PROGRESS when the current release has more chapters) |
| `clientSentStatsAreIgnored` | NFR-4 "ignores client totals" (forged stats, deltas and `correct` in the batch are ignored) |
| `oversizedOrDuplicateBatchIsRejected` | rejected row "more than 100 rows → 2003; two rows for one choice group → 1001" |

## GroupCloseBulkFlowTest (1 new)

| Test | Covers |
|---|---|
| `classGroupTakesFortySeatsAndRefusesTheFortyFirst` | TCH-1 forty-seat cap (bulk 40 in a CLASS group after consent, 41st refused 3006) |

## GroupSlotFlowTest (1 new)

| Test | Covers |
|---|---|
| `passwordOtpAndPinAreStoredAsSaltedHashes` | NFR-3 hashing of password, OTP and PIN (seat codes are clear by design, not tested) |

## Not covered here

- NFR-1 (load time, p95 under load) and NFR-2 ops items: need a load tool / drill, not a unit or integration test.
- Achievements and concept mastery (LRN-3, ADL-3): not implemented (`/api/achievements` answers 501).
