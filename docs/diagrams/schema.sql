--
-- PostgreSQL database dump
--


-- Dumped from database version 16.15
-- Dumped by pg_dump version 16.15

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: account; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.account (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone,
    display_name character varying(80),
    email character varying(160),
    email_verified_at timestamp(6) with time zone,
    failed_attempts integer,
    locked_until timestamp(6) with time zone,
    must_change_password boolean DEFAULT false NOT NULL,
    phone character varying(20),
    token_version integer DEFAULT 0 NOT NULL
);


--
-- Name: account_role; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.account_role (
    id uuid NOT NULL,
    granted_at timestamp(6) with time zone,
    role character varying(255),
    account_id uuid NOT NULL,
    CONSTRAINT account_role_role_check CHECK (((role)::text = ANY ((ARRAY['ADMIN'::character varying, 'CHILD'::character varying, 'GAME_EDITOR'::character varying, 'GAME_MANAGER'::character varying, 'GAME_REVIEWER'::character varying, 'GUEST'::character varying, 'PARENT'::character varying, 'TEACHER'::character varying])::text[])))
);


--
-- Name: achievement; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.achievement (
    id uuid NOT NULL,
    certificate_url character varying(255),
    code character varying(40),
    earned_at timestamp(6) with time zone,
    slot_id uuid NOT NULL
);


--
-- Name: ai_key; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ai_key (
    id uuid NOT NULL,
    alias character varying(40),
    cool_down_until timestamp(6) with time zone,
    created_at timestamp(6) with time zone,
    enabled boolean DEFAULT false NOT NULL,
    last4 character varying(4),
    last_error character varying(255),
    last_test_at timestamp(6) with time zone,
    last_test_ok boolean DEFAULT false NOT NULL,
    priority integer,
    provider character varying(255),
    secret_enc text,
    created_by_id uuid,
    CONSTRAINT ai_key_provider_check CHECK (((provider)::text = ANY ((ARRAY['GEMINI'::character varying, 'OPENROUTER'::character varying])::text[])))
);


--
-- Name: asset; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.asset (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone,
    height integer,
    kind character varying(255),
    path character varying(255),
    sha256 character varying(64),
    size_bytes bigint,
    width integer,
    uploaded_by_id uuid,
    chapter_code character varying(8),
    label character varying(120),
    source_sha256 character varying(64),
    CONSTRAINT asset_kind_check CHECK (((kind)::text = ANY ((ARRAY['AUDIO'::character varying, 'IMAGE'::character varying])::text[])))
);


--
-- Name: chapter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chapter (
    id uuid NOT NULL,
    chapter_number integer,
    code character varying(16),
    created_at timestamp(6) with time zone,
    title character varying(120)
);


--
-- Name: chapter_draft; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.chapter_draft (
    id uuid NOT NULL,
    change_note character varying(255),
    content text,
    created_at timestamp(6) with time zone,
    locked_until timestamp(6) with time zone,
    review_note character varying(500),
    reviewed_at timestamp(6) with time zone,
    revision integer DEFAULT 0 NOT NULL,
    status character varying(255),
    updated_at timestamp(6) with time zone,
    chapter_id uuid NOT NULL,
    editor_id uuid,
    locked_by_id uuid,
    reviewer_id uuid,
    change_class character varying(255),
    reviewed_revision integer,
    submitted_at timestamp(6) with time zone,
    CONSTRAINT chapter_draft_change_class_check CHECK (((change_class)::text = ANY ((ARRAY['MAJOR'::character varying, 'MINOR'::character varying, 'PATCH'::character varying])::text[]))),
    CONSTRAINT chapter_draft_status_check CHECK (((status)::text = ANY ((ARRAY['APPROVED'::character varying, 'DRAFT'::character varying, 'REJECTED'::character varying, 'RELEASED'::character varying, 'SUBMITTED'::character varying])::text[])))
);


--
-- Name: choice_event; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.choice_event (
    id uuid NOT NULL,
    attempt_index integer,
    build_version character varying(32),
    chapter integer,
    choice_id character varying(64),
    chosen_at timestamp(6) with time zone,
    effects text,
    received_at timestamp(6) with time zone,
    scene_id character varying(64),
    slot_id uuid NOT NULL
);


--
-- Name: concept_mastery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.concept_mastery (
    id uuid NOT NULL,
    answers integer,
    concept character varying(40),
    p_mastery numeric(5,4),
    updated_at timestamp(6) with time zone,
    slot_id uuid NOT NULL
);


--
-- Name: credential; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.credential (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone,
    email_at_provider character varying(160),
    email_verified_at timestamp(6) with time zone,
    last_used_at timestamp(6) with time zone,
    password_hash character varying(72),
    provider character varying(255),
    subject character varying(255),
    account_id uuid NOT NULL,
    CONSTRAINT credential_provider_check CHECK (((provider)::text = ANY ((ARRAY['GOOGLE'::character varying, 'PASSWORD'::character varying])::text[])))
);


--
-- Name: daily_summary; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.daily_summary (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone,
    model character varying(80),
    prompt_version character varying(32),
    source character varying(255),
    summary text,
    summary_date date,
    group_id uuid,
    slot_id uuid,
    CONSTRAINT daily_summary_source_check CHECK (((source)::text = ANY ((ARRAY['LLM'::character varying, 'RULE'::character varying])::text[])))
);


--
-- Name: entitlement; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.entitlement (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone,
    expires_on date,
    kind character varying(255),
    reason character varying(255),
    source character varying(255),
    starts_on date,
    account_id uuid NOT NULL,
    granted_by_id uuid,
    transaction_id uuid,
    CONSTRAINT entitlement_kind_check CHECK (((kind)::text = ANY ((ARRAY['PARENT'::character varying, 'TEACHER'::character varying])::text[]))),
    CONSTRAINT entitlement_source_check CHECK (((source)::text = ANY ((ARRAY['ADMIN'::character varying, 'PAYMENT'::character varying])::text[])))
);


--
-- Name: game_release; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.game_release (
    id uuid NOT NULL,
    is_current boolean DEFAULT false NOT NULL,
    note character varying(255),
    released_at timestamp(6) with time zone,
    version character varying(16),
    released_by_id uuid,
    change_class character varying(255),
    recall_reason character varying(255),
    recalled_at timestamp(6) with time zone,
    recalled_by_id uuid,
    CONSTRAINT game_release_change_class_check CHECK (((change_class)::text = ANY ((ARRAY['MAJOR'::character varying, 'MINOR'::character varying, 'PATCH'::character varying])::text[])))
);


--
-- Name: group_report; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.group_report (
    id uuid NOT NULL,
    formula_version character varying(32),
    frozen_at timestamp(6) with time zone,
    summary text,
    group_id uuid NOT NULL
);


--
-- Name: group_report_row; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.group_report_row (
    id uuid NOT NULL,
    figures text,
    report_id uuid NOT NULL,
    slot_id uuid NOT NULL
);


--
-- Name: learner_group; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.learner_group (
    id uuid NOT NULL,
    closed_at timestamp(6) with time zone,
    consent_confirmed_at timestamp(6) with time zone,
    context character varying(255),
    name character varying(80),
    open_context character varying(255),
    opened_at timestamp(6) with time zone,
    slot_limit integer,
    owner_id uuid NOT NULL,
    post_test_enabled_at timestamp(6) with time zone,
    CONSTRAINT learner_group_context_check CHECK (((context)::text = ANY ((ARRAY['CLASS'::character varying, 'FAMILY'::character varying])::text[]))),
    CONSTRAINT learner_group_open_context_check CHECK (((open_context)::text = ANY ((ARRAY['CLASS'::character varying, 'FAMILY'::character varying])::text[])))
);


--
-- Name: learner_slot; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.learner_slot (
    id uuid NOT NULL,
    archived_at timestamp(6) with time zone,
    badge character varying(40),
    code character varying(16),
    created_at timestamp(6) with time zone,
    display_name character varying(40),
    failed_attempts integer,
    locked_until timestamp(6) with time zone,
    pin_hash character varying(72),
    status character varying(255),
    wiped_at timestamp(6) with time zone,
    group_id uuid NOT NULL,
    CONSTRAINT learner_slot_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'ARCHIVED'::character varying, 'WIPED'::character varying])::text[])))
);


--
-- Name: mini_game_result; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.mini_game_result (
    id uuid NOT NULL,
    attempt_index integer,
    build_version character varying(32),
    chapter integer,
    chosen text,
    concept character varying(40),
    correct boolean,
    game_id character varying(40),
    item_id character varying(64),
    played_at timestamp(6) with time zone,
    received_at timestamp(6) with time zone,
    slot_id uuid NOT NULL
);


--
-- Name: plan; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.plan (
    id uuid NOT NULL,
    kind character varying(255),
    months integer,
    price bigint,
    updated_at timestamp(6) with time zone,
    updated_by_id uuid,
    CONSTRAINT plan_kind_check CHECK (((kind)::text = ANY ((ARRAY['PARENT'::character varying, 'TEACHER'::character varying])::text[])))
);


--
-- Name: question; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.question (
    id uuid NOT NULL,
    correct_index integer,
    created_at timestamp(6) with time zone,
    options text,
    pool character varying(40),
    source character varying(160),
    standard character varying(80),
    stem text,
    chapter integer,
    concept character varying(40),
    external_id character varying(80),
    retired_at timestamp(6) with time zone,
    replaces_id uuid
);


--
-- Name: quiz; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quiz (
    id uuid NOT NULL,
    due_at timestamp(6) with time zone,
    published_at timestamp(6) with time zone,
    question_ids text,
    title character varying(120),
    group_id uuid NOT NULL,
    owner_id uuid NOT NULL,
    chapter integer,
    kind character varying(255),
    CONSTRAINT quiz_kind_check CHECK (((kind)::text = ANY ((ARRAY['CLASS_TEST'::character varying, 'POST_TEST'::character varying])::text[])))
);


--
-- Name: quiz_answer; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quiz_answer (
    id uuid NOT NULL,
    chosen_index integer,
    correct boolean,
    question_id uuid NOT NULL,
    quiz_result_id uuid NOT NULL
);


--
-- Name: quiz_result; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.quiz_result (
    id uuid NOT NULL,
    score integer,
    submitted_at timestamp(6) with time zone,
    quiz_id uuid NOT NULL,
    slot_id uuid NOT NULL
);


--
-- Name: release_chapter; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.release_chapter (
    id uuid NOT NULL,
    content text,
    content_hash character varying(64),
    chapter_id uuid NOT NULL,
    release_id uuid NOT NULL
);


--
-- Name: role_grant_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.role_grant_log (
    id uuid NOT NULL,
    created_at timestamp(6) with time zone,
    granted boolean,
    reason character varying(255),
    role character varying(16),
    actor_id uuid NOT NULL,
    target_id uuid NOT NULL
);


--
-- Name: run_state; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.run_state (
    id uuid NOT NULL,
    chapters_done text,
    goal integer,
    happiness integer,
    risk integer,
    saving integer,
    updated_at timestamp(6) with time zone,
    wealth integer,
    slot_id uuid NOT NULL,
    attempt_index integer
);


--
-- Name: transaction; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.transaction (
    id uuid NOT NULL,
    amount bigint,
    created_at timestamp(6) with time zone,
    gateway_ref character varying(64),
    kind character varying(255),
    order_code bigint,
    paid_at timestamp(6) with time zone,
    purpose character varying(255),
    status character varying(255),
    account_id uuid NOT NULL,
    bank_account_name character varying(128),
    bank_account_number character varying(32),
    bank_bin character varying(16),
    expires_at timestamp(6) with time zone,
    qr_code character varying(512),
    transfer_note character varying(64),
    months integer,
    needs_review boolean DEFAULT false NOT NULL,
    note character varying(255),
    CONSTRAINT transaction_kind_check CHECK (((kind)::text = ANY ((ARRAY['PARENT'::character varying, 'TEACHER'::character varying])::text[]))),
    CONSTRAINT transaction_purpose_check CHECK (((purpose)::text = ANY ((ARRAY['NEW'::character varying, 'RENEW'::character varying])::text[]))),
    CONSTRAINT transaction_status_check CHECK (((status)::text = ANY ((ARRAY['FAILED'::character varying, 'PAID'::character varying, 'PENDING'::character varying])::text[])))
);


--
-- Name: verification_token; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.verification_token (
    id uuid NOT NULL,
    attempts integer,
    created_at timestamp(6) with time zone,
    expires_at timestamp(6) with time zone,
    purpose character varying(255),
    token_hash character varying(64),
    used_at timestamp(6) with time zone,
    account_id uuid NOT NULL,
    CONSTRAINT verification_token_purpose_check CHECK (((purpose)::text = ANY ((ARRAY['RESET_PASSWORD'::character varying, 'VERIFY_EMAIL'::character varying])::text[])))
);


--
-- Name: account account_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account
    ADD CONSTRAINT account_pkey PRIMARY KEY (id);


--
-- Name: account_role account_role_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_role
    ADD CONSTRAINT account_role_pkey PRIMARY KEY (id);


--
-- Name: achievement achievement_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.achievement
    ADD CONSTRAINT achievement_pkey PRIMARY KEY (id);


--
-- Name: ai_key ai_key_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_key
    ADD CONSTRAINT ai_key_pkey PRIMARY KEY (id);


--
-- Name: asset asset_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset
    ADD CONSTRAINT asset_pkey PRIMARY KEY (id);


--
-- Name: chapter_draft chapter_draft_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chapter_draft
    ADD CONSTRAINT chapter_draft_pkey PRIMARY KEY (id);


--
-- Name: chapter chapter_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chapter
    ADD CONSTRAINT chapter_pkey PRIMARY KEY (id);


--
-- Name: choice_event choice_event_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.choice_event
    ADD CONSTRAINT choice_event_pkey PRIMARY KEY (id);


--
-- Name: concept_mastery concept_mastery_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.concept_mastery
    ADD CONSTRAINT concept_mastery_pkey PRIMARY KEY (id);


--
-- Name: credential credential_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credential
    ADD CONSTRAINT credential_pkey PRIMARY KEY (id);


--
-- Name: daily_summary daily_summary_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_summary
    ADD CONSTRAINT daily_summary_pkey PRIMARY KEY (id);


--
-- Name: entitlement entitlement_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.entitlement
    ADD CONSTRAINT entitlement_pkey PRIMARY KEY (id);


--
-- Name: game_release game_release_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_release
    ADD CONSTRAINT game_release_pkey PRIMARY KEY (id);


--
-- Name: group_report group_report_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_report
    ADD CONSTRAINT group_report_pkey PRIMARY KEY (id);


--
-- Name: group_report_row group_report_row_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_report_row
    ADD CONSTRAINT group_report_row_pkey PRIMARY KEY (id);


--
-- Name: learner_group learner_group_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.learner_group
    ADD CONSTRAINT learner_group_pkey PRIMARY KEY (id);


--
-- Name: learner_slot learner_slot_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.learner_slot
    ADD CONSTRAINT learner_slot_pkey PRIMARY KEY (id);


--
-- Name: mini_game_result mini_game_result_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mini_game_result
    ADD CONSTRAINT mini_game_result_pkey PRIMARY KEY (id);


--
-- Name: plan plan_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan
    ADD CONSTRAINT plan_pkey PRIMARY KEY (id);


--
-- Name: question question_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.question
    ADD CONSTRAINT question_pkey PRIMARY KEY (id);


--
-- Name: quiz_answer quiz_answer_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz_answer
    ADD CONSTRAINT quiz_answer_pkey PRIMARY KEY (id);


--
-- Name: quiz quiz_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz
    ADD CONSTRAINT quiz_pkey PRIMARY KEY (id);


--
-- Name: quiz_result quiz_result_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz_result
    ADD CONSTRAINT quiz_result_pkey PRIMARY KEY (id);


--
-- Name: release_chapter release_chapter_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.release_chapter
    ADD CONSTRAINT release_chapter_pkey PRIMARY KEY (id);


--
-- Name: role_grant_log role_grant_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_grant_log
    ADD CONSTRAINT role_grant_log_pkey PRIMARY KEY (id);


--
-- Name: run_state run_state_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_state
    ADD CONSTRAINT run_state_pkey PRIMARY KEY (id);


--
-- Name: transaction transaction_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transaction
    ADD CONSTRAINT transaction_pkey PRIMARY KEY (id);


--
-- Name: daily_summary uk373wl7tkfob3ql58lo5oboaje; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_summary
    ADD CONSTRAINT uk373wl7tkfob3ql58lo5oboaje UNIQUE (group_id, summary_date);


--
-- Name: ai_key uk4sxfce3hei3v6simr9f51fb8r; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_key
    ADD CONSTRAINT uk4sxfce3hei3v6simr9f51fb8r UNIQUE (alias);


--
-- Name: question uk5094jg1ixagc26gjqecpo4ws1; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.question
    ADD CONSTRAINT uk5094jg1ixagc26gjqecpo4ws1 UNIQUE (external_id);


--
-- Name: quiz uk8i65sm8qiukoqjtc4dwdnnqbu; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz
    ADD CONSTRAINT uk8i65sm8qiukoqjtc4dwdnnqbu UNIQUE (group_id, kind, chapter);


--
-- Name: transaction uk8s4fvmixpsgjut63stma3upf9; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transaction
    ADD CONSTRAINT uk8s4fvmixpsgjut63stma3upf9 UNIQUE (gateway_ref);


--
-- Name: quiz_result uk9cycm36pelcjp8nrxxyni5btf; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz_result
    ADD CONSTRAINT uk9cycm36pelcjp8nrxxyni5btf UNIQUE (quiz_id, slot_id);


--
-- Name: concept_mastery uka81mb57nufcxbfobndf5xd9lx; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.concept_mastery
    ADD CONSTRAINT uka81mb57nufcxbfobndf5xd9lx UNIQUE (slot_id, concept);


--
-- Name: account_role ukb67d904mkj2ur6w9917h6ycm; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_role
    ADD CONSTRAINT ukb67d904mkj2ur6w9917h6ycm UNIQUE (account_id, role);


--
-- Name: learner_group ukc0pxnbkfkqt74apjw864yand8; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.learner_group
    ADD CONSTRAINT ukc0pxnbkfkqt74apjw864yand8 UNIQUE (owner_id, open_context);


--
-- Name: mini_game_result ukd97yuwgiikmeqg1f7pxpbh8gs; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mini_game_result
    ADD CONSTRAINT ukd97yuwgiikmeqg1f7pxpbh8gs UNIQUE (slot_id, chapter, attempt_index, game_id, item_id);


--
-- Name: quiz_answer ukeka8sb10lv666pajnaqyx5o9u; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz_answer
    ADD CONSTRAINT ukeka8sb10lv666pajnaqyx5o9u UNIQUE (quiz_result_id, question_id);


--
-- Name: achievement uketlrpfbvygo67qiuhfulmmo0m; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.achievement
    ADD CONSTRAINT uketlrpfbvygo67qiuhfulmmo0m UNIQUE (slot_id, code);


--
-- Name: entitlement ukfbl5sisu1isprb5rtkiba689h; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.entitlement
    ADD CONSTRAINT ukfbl5sisu1isprb5rtkiba689h UNIQUE (transaction_id);


--
-- Name: asset ukhavdi9nvtgv4k7kuvmkmmrly3; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset
    ADD CONSTRAINT ukhavdi9nvtgv4k7kuvmkmmrly3 UNIQUE (sha256);


--
-- Name: game_release ukjk6uyc58y65mw9fpkkgv0h6c; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_release
    ADD CONSTRAINT ukjk6uyc58y65mw9fpkkgv0h6c UNIQUE (version);


--
-- Name: credential ukjuk8uev7uc0yry8v97hr7ofyq; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credential
    ADD CONSTRAINT ukjuk8uev7uc0yry8v97hr7ofyq UNIQUE (provider, subject);


--
-- Name: learner_slot ukkfw9qr93ayb7kh2u5elka87r6; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.learner_slot
    ADD CONSTRAINT ukkfw9qr93ayb7kh2u5elka87r6 UNIQUE (code);


--
-- Name: release_chapter uklhrepf71p4u1pecjiqnn5175b; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.release_chapter
    ADD CONSTRAINT uklhrepf71p4u1pecjiqnn5175b UNIQUE (release_id, chapter_id);


--
-- Name: choice_event ukltaln0yw5tjhswuwkjsnkoops; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.choice_event
    ADD CONSTRAINT ukltaln0yw5tjhswuwkjsnkoops UNIQUE (slot_id, chapter, attempt_index, scene_id);


--
-- Name: daily_summary ukm8v9f3ipw864r6lxppvrivd54; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_summary
    ADD CONSTRAINT ukm8v9f3ipw864r6lxppvrivd54 UNIQUE (slot_id, summary_date);


--
-- Name: transaction ukp0tgk2k80nom3doybe9aov9fr; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transaction
    ADD CONSTRAINT ukp0tgk2k80nom3doybe9aov9fr UNIQUE (order_code);


--
-- Name: account ukq0uja26qgu1atulenwup9rxyr; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account
    ADD CONSTRAINT ukq0uja26qgu1atulenwup9rxyr UNIQUE (email);


--
-- Name: plan ukqkn1ldn2vui1iymrqcar9xll7; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan
    ADD CONSTRAINT ukqkn1ldn2vui1iymrqcar9xll7 UNIQUE (kind);


--
-- Name: chapter ukrsa95c1s3be3yca5dkiuvc54b; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chapter
    ADD CONSTRAINT ukrsa95c1s3be3yca5dkiuvc54b UNIQUE (code);


--
-- Name: verification_token uksc9pahjl1ndcyqay7276cs532; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.verification_token
    ADD CONSTRAINT uksc9pahjl1ndcyqay7276cs532 UNIQUE (token_hash);


--
-- Name: run_state ukwplakq2foqwo7nwb6tsvggee; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_state
    ADD CONSTRAINT ukwplakq2foqwo7nwb6tsvggee UNIQUE (slot_id);


--
-- Name: verification_token verification_token_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.verification_token
    ADD CONSTRAINT verification_token_pkey PRIMARY KEY (id);


--
-- Name: role_grant_log fk12mi6h3of31jl55k0jyletcmc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_grant_log
    ADD CONSTRAINT fk12mi6h3of31jl55k0jyletcmc FOREIGN KEY (target_id) REFERENCES public.account(id);


--
-- Name: account_role fk1f8y4iy71kb1arff79s71j0dh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.account_role
    ADD CONSTRAINT fk1f8y4iy71kb1arff79s71j0dh FOREIGN KEY (account_id) REFERENCES public.account(id);


--
-- Name: plan fk1lhf9b8g4rm8vut0bak25504i; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.plan
    ADD CONSTRAINT fk1lhf9b8g4rm8vut0bak25504i FOREIGN KEY (updated_by_id) REFERENCES public.account(id);


--
-- Name: daily_summary fk26ildvpq2ytxbpyttio8df542; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_summary
    ADD CONSTRAINT fk26ildvpq2ytxbpyttio8df542 FOREIGN KEY (group_id) REFERENCES public.learner_group(id);


--
-- Name: daily_summary fk3lrygomcnk1moq7pxx5bsr51s; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.daily_summary
    ADD CONSTRAINT fk3lrygomcnk1moq7pxx5bsr51s FOREIGN KEY (slot_id) REFERENCES public.learner_slot(id);


--
-- Name: release_chapter fk598p69412u5jt7l4p3wnxbpu8; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.release_chapter
    ADD CONSTRAINT fk598p69412u5jt7l4p3wnxbpu8 FOREIGN KEY (release_id) REFERENCES public.game_release(id);


--
-- Name: transaction fk6g20fcr3bhr6bihgy24rq1r1b; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.transaction
    ADD CONSTRAINT fk6g20fcr3bhr6bihgy24rq1r1b FOREIGN KEY (account_id) REFERENCES public.account(id);


--
-- Name: entitlement fk7pk9rncnws474khitn6mctqc4; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.entitlement
    ADD CONSTRAINT fk7pk9rncnws474khitn6mctqc4 FOREIGN KEY (account_id) REFERENCES public.account(id);


--
-- Name: choice_event fk9h8b4g8o9fo07hjlydgmfysgh; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.choice_event
    ADD CONSTRAINT fk9h8b4g8o9fo07hjlydgmfysgh FOREIGN KEY (slot_id) REFERENCES public.learner_slot(id);


--
-- Name: role_grant_log fk9j02nv4ejgw63p4mqp0u7fvn0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.role_grant_log
    ADD CONSTRAINT fk9j02nv4ejgw63p4mqp0u7fvn0 FOREIGN KEY (actor_id) REFERENCES public.account(id);


--
-- Name: quiz_answer fkb4r0wfjca778oc7doyyps5boi; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz_answer
    ADD CONSTRAINT fkb4r0wfjca778oc7doyyps5boi FOREIGN KEY (quiz_result_id) REFERENCES public.quiz_result(id);


--
-- Name: run_state fkbob5h87djadprrgtn4k0x85dj; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.run_state
    ADD CONSTRAINT fkbob5h87djadprrgtn4k0x85dj FOREIGN KEY (slot_id) REFERENCES public.learner_slot(id);


--
-- Name: credential fkcgjhhuj1mkchkk8r00xhdqvo0; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.credential
    ADD CONSTRAINT fkcgjhhuj1mkchkk8r00xhdqvo0 FOREIGN KEY (account_id) REFERENCES public.account(id);


--
-- Name: achievement fkcpo0a7p6obhx7lj64gmvcscu1; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.achievement
    ADD CONSTRAINT fkcpo0a7p6obhx7lj64gmvcscu1 FOREIGN KEY (slot_id) REFERENCES public.learner_slot(id);


--
-- Name: quiz_result fkd49de4d3rwgtndq0n51w1isbx; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz_result
    ADD CONSTRAINT fkd49de4d3rwgtndq0n51w1isbx FOREIGN KEY (quiz_id) REFERENCES public.quiz(id);


--
-- Name: entitlement fke8t4d6kdt11cn2oiwxsl9px3x; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.entitlement
    ADD CONSTRAINT fke8t4d6kdt11cn2oiwxsl9px3x FOREIGN KEY (transaction_id) REFERENCES public.transaction(id);


--
-- Name: game_release fkf66ym97fwxj23edfkpud1n61o; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_release
    ADD CONSTRAINT fkf66ym97fwxj23edfkpud1n61o FOREIGN KEY (recalled_by_id) REFERENCES public.account(id);


--
-- Name: concept_mastery fkfo4m4pg0ofvhpasr6nxrx3wob; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.concept_mastery
    ADD CONSTRAINT fkfo4m4pg0ofvhpasr6nxrx3wob FOREIGN KEY (slot_id) REFERENCES public.learner_slot(id);


--
-- Name: quiz_result fkhhc6rv9mo400iajlj523hfw8b; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz_result
    ADD CONSTRAINT fkhhc6rv9mo400iajlj523hfw8b FOREIGN KEY (slot_id) REFERENCES public.learner_slot(id);


--
-- Name: asset fkho9pa6vkhrvpctejbjrj7xwgc; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.asset
    ADD CONSTRAINT fkho9pa6vkhrvpctejbjrj7xwgc FOREIGN KEY (uploaded_by_id) REFERENCES public.account(id);


--
-- Name: group_report_row fkicsxtnxt308j2cqg9c014wnnd; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_report_row
    ADD CONSTRAINT fkicsxtnxt308j2cqg9c014wnnd FOREIGN KEY (slot_id) REFERENCES public.learner_slot(id);


--
-- Name: release_chapter fkj4nv667yfk70se4bxqf2sf303; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.release_chapter
    ADD CONSTRAINT fkj4nv667yfk70se4bxqf2sf303 FOREIGN KEY (chapter_id) REFERENCES public.chapter(id);


--
-- Name: quiz fkjc7rwb1vgtfaf7l4lx2xycngs; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz
    ADD CONSTRAINT fkjc7rwb1vgtfaf7l4lx2xycngs FOREIGN KEY (owner_id) REFERENCES public.account(id);


--
-- Name: learner_group fkk0cv49cfegs2kijccgavqokkx; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.learner_group
    ADD CONSTRAINT fkk0cv49cfegs2kijccgavqokkx FOREIGN KEY (owner_id) REFERENCES public.account(id);


--
-- Name: game_release fkk6fgefnok6ngbon9kqwspxll3; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.game_release
    ADD CONSTRAINT fkk6fgefnok6ngbon9kqwspxll3 FOREIGN KEY (released_by_id) REFERENCES public.account(id);


--
-- Name: entitlement fkkax7dmb4o3ktp6mn5adiedufl; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.entitlement
    ADD CONSTRAINT fkkax7dmb4o3ktp6mn5adiedufl FOREIGN KEY (granted_by_id) REFERENCES public.account(id);


--
-- Name: ai_key fkkic21fnh5bqi5pe36c8gd25bv; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ai_key
    ADD CONSTRAINT fkkic21fnh5bqi5pe36c8gd25bv FOREIGN KEY (created_by_id) REFERENCES public.account(id);


--
-- Name: chapter_draft fkkvfmvskaiyb74dmntt3a6dpac; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chapter_draft
    ADD CONSTRAINT fkkvfmvskaiyb74dmntt3a6dpac FOREIGN KEY (chapter_id) REFERENCES public.chapter(id);


--
-- Name: group_report_row fkld57m075ra2hxen3yy6lbw037; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_report_row
    ADD CONSTRAINT fkld57m075ra2hxen3yy6lbw037 FOREIGN KEY (report_id) REFERENCES public.group_report(id);


--
-- Name: quiz_answer fklhy8unwrvsly7799l2t90k0r9; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz_answer
    ADD CONSTRAINT fklhy8unwrvsly7799l2t90k0r9 FOREIGN KEY (question_id) REFERENCES public.question(id);


--
-- Name: learner_slot fkmy6odyx0ysneila9kg2t2h07i; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.learner_slot
    ADD CONSTRAINT fkmy6odyx0ysneila9kg2t2h07i FOREIGN KEY (group_id) REFERENCES public.learner_group(id);


--
-- Name: quiz fkni5y2ye5qc1wcrndef6ne9ljo; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.quiz
    ADD CONSTRAINT fkni5y2ye5qc1wcrndef6ne9ljo FOREIGN KEY (group_id) REFERENCES public.learner_group(id);


--
-- Name: chapter_draft fknj5tovf5nqyjwb34pd18xgkgu; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chapter_draft
    ADD CONSTRAINT fknj5tovf5nqyjwb34pd18xgkgu FOREIGN KEY (locked_by_id) REFERENCES public.account(id);


--
-- Name: question fkpaul2nh8avxfsi04xyby7p602; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.question
    ADD CONSTRAINT fkpaul2nh8avxfsi04xyby7p602 FOREIGN KEY (replaces_id) REFERENCES public.question(id);


--
-- Name: mini_game_result fkrr5k84x4fdojwyouiurigpcuj; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.mini_game_result
    ADD CONSTRAINT fkrr5k84x4fdojwyouiurigpcuj FOREIGN KEY (slot_id) REFERENCES public.learner_slot(id);


--
-- Name: verification_token fks8je6hs7qhfs93obh8dqml9fe; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.verification_token
    ADD CONSTRAINT fks8je6hs7qhfs93obh8dqml9fe FOREIGN KEY (account_id) REFERENCES public.account(id);


--
-- Name: chapter_draft fksogub7uol5pms9chux1abhu4k; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chapter_draft
    ADD CONSTRAINT fksogub7uol5pms9chux1abhu4k FOREIGN KEY (editor_id) REFERENCES public.account(id);


--
-- Name: chapter_draft fkt5w97qla4p1blpi25ohrgl5hl; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.chapter_draft
    ADD CONSTRAINT fkt5w97qla4p1blpi25ohrgl5hl FOREIGN KEY (reviewer_id) REFERENCES public.account(id);


--
-- Name: group_report fktltaov4e96oxpua0ajyqpo7fe; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.group_report
    ADD CONSTRAINT fktltaov4e96oxpua0ajyqpo7fe FOREIGN KEY (group_id) REFERENCES public.learner_group(id);


--
-- PostgreSQL database dump complete
--


