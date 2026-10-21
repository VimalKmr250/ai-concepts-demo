# Demo script

This is a click-by-click walkthrough for presenting the showcase.

**Setup:** start the apps as described in the [README](README.md) (ai-app, mcp-server for demo 7, procurement-agent for demo 12), then open http://localhost:8080. Every demo page has **Try** buttons with prepared inputs, and the same calls are in [http/requests.http](http/requests.http).

**Timing:** the times below are for qwen3-4b on a 4 GB laptop GPU. Hosted models are much faster.

---

## 1 · Chat with persistent memory and streaming (about 3 min)

**Story:** Acme Connect's support assistant "Ava" remembers who you are, even after the server restarts.

1. Open **1 Chat & memory**. The conversation id is `priya-demo`.
2. Click **"1 · Introduce yourself"**. The reply streams in token by token over SSE.
3. Click **"2 · Test memory"**. The assistant answers *Priya* and *Pro plan*.
4. Click **View stored memory** to see the rows in Postgres table `spring_ai_chat_memory`.
5. **Restart ai-app** (stop it, then `./mvnw -pl ai-app spring-boot:run`) and reload the page. The chat shows *"Restored N messages from Postgres"*. Click **"2 · Test memory"** again and it still knows Priya.
6. Click **New conversation** and ask the same question. A fresh id means no memory.

**Point out:** `MessageChatMemoryAdvisor` + `JdbcChatMemoryRepository`; the memory window is the last 20 messages; streaming returns a `Flux<String>` from `ChatClient.stream()`.

## 2 · Guardrails (about 3 min)

**Story:** Acme Bank's public chat must never leak customer data to the model provider, and must not be hijacked.

| Try button | Expected result |
|---|---|
| **PII in message** | Normal answer. Pills show `1× CARD masked`, `1× EMAIL masked` and `1× PHONE masked`. **What the LLM saw** shows `[CARD] … [EMAIL] … [PHONE]`. |
| **Prompt injection** | Red **BLOCKED · prompt-injection** (rule `override-instructions`), HTTP 400 problem detail. The model is never called. |
| **Off-topic** ("poem about cats") | Polite refusal from the **topic guard**. The model is not called; the pill says *answered by topic guard*. |
| **Denylisted term** | Spring AI's `SafeGuardAdvisor` answers with its refusal message. |
| **Normal question** | Passes all guards and gets a normal answer. |

**Point out:**
- The guards are ordinary ChatClient advisors, ordered *before* the memory advisor.
- Open demo 1's memory view for conversation `bank-demo` (type it in the conversation id field): the stored messages contain only `[CARD]` placeholders.
- The output guard would also withhold a leaked system prompt; there's a unit test for that.

## 3 · RAG with citations (about 4 min)

**Story:** employees ask HR/IT policy questions and get answers from the Acme handbook (5 Markdown files + 1 PDF), with sources.

| Try button | Expected result |
|---|---|
| **Leave carry-over** | "max **5 days**, until **31 March** [1]". The cited source `leave-policy.md` is highlighted. |
| **Business class?** | Premium economy for flights of 6 hours or more; business class needs VP approval. |
| **Hotel cap London** (FINANCE filter) | EUR 250 per night. Only FINANCE-category chunks are retrieved. |
| **Lost laptop** (IT filter) | Report within 1 hour to the IT helpdesk or security@. |
| **Learning budget (PDF)** | EUR 1,500 per year, doesn't roll over. Source: `benefits-guide.pdf`, parsed by Tika. |
| **Not in handbook** (CEO salary) | "I couldn't find that in the Acme handbook documents." No hallucination. |

Then upload your own PDF under **Knowledge base** and ask about it. Uploading the same file again is a no-op (sha256 dedupe), and **Delete** removes its chunks.

**Point out:** the ETL steps (Tika → TokenTextSplitter → metadata → pgvector); `RetrievalAugmentationAdvisor` with top-k, threshold and a metadata filter; numbered sources, with each one marked *cited* or *retrieved, not cited*.

## 4 · Tools with human-in-the-loop (about 4 min)

**Story:** the IT helpdesk agent can look up employees and laptops and open tickets by itself, but a person must approve password resets and hardware loans.

1. **"Broken laptop → ticket"**: the tool trace shows `lookupEmployee` → `listAssets` → `createTicket` (all *auto*), and a new ticket `INC-10xx` appears in **Tickets**.
2. **"Password reset (approval)"**: you get HTTP 202 with *waiting for human approval*. The proposed call `resetPassword {"username":"asmith",…}` and the reason are shown, and the sidebar badge shows 1.
3. Click **Approve**. The tool runs, the agent finishes ("reset link sent to alice.smith@…"), and the **Audit log** shows `RESET_PASSWORD` and `APPROVED`.
4. **"Loaner laptop (approval)"** → **Reject…** with a reason. The agent tells the user it was not done, and the audit log shows `REJECTED`.

**Point out:**
- In Spring AI 2 the tool loop runs in `ToolCallingAdvisor`. A small decorator around `ToolCallingManager` pauses the loop for methods annotated `@RequiresApproval`, and the parked conversation resumes after the decision.
- Small models sometimes skip a lookup or choose a different tool order. The trace makes that visible.

## 5 · Agent patterns (about 5 min)

The five workflow patterns from Anthropic's *Building effective agents*, each on its own tab:

| Tab | Use case | What to look at |
|---|---|---|
| **Chain** | Marketing copy pipeline | Four step cards (features → draft → brand voice → final Markdown), each with its own timing. |
| **Routing** | Support-inbox triage | **Triage all 5 emails**: each gets a route pill (BILLING / TECHNICAL / ACCOUNT), the reason, and a reply signed by the specialist. |
| **Parallelization** | Impact of a price increase on 4 stakeholder groups | The bar chart: wall-clock time ≈ the slowest call, versus the sum when run sequentially (about **3–4× faster**, since LM Studio serves 4 requests in parallel). |
| **Orchestrator-workers** | "Acme Shield" launch content kit | The orchestrator's plan (2–4 tasks it chose itself), then the worker outputs. |
| **Evaluator-optimizer** | Java `isValidIban` with a reviewer | Rounds with verdict, feedback and revised code; it stops at PASS or after 3 rounds. With a 4B model the strict reviewer often keeps asking for changes until the round limit, which shows why loops need a cap. *Slowest demo locally (3–4 min).* |

## 6 · Structured extraction (about 3 min)

| Sample | Expected result |
|---|---|
| **Invoice → Clean invoice (UK)** | **VALID**: Northwind, 3 line items, subtotal 2,742.50 + VAT 548.50 = 3,291.00 GBP. |
| **Invoice → Messy forwarded email (DE)** | **VALID**: German number and date formats are normalized (`1.504,80` → 1504.80, `02.10.2026` → 2026-10-02). |
| **Invoice → Total doesn't add up** | **NEEDS HUMAN REVIEW**: `total: subtotal 1279.0 + tax 0.0 = 1279.0 but total is 1379.0`. The re-check returns the same values, so the system escalates instead of letting the model "fix" the numbers. |
| **Meeting notes → Launch sync transcript** | A summary, 2–3 decisions, and action items for Tom (HIGH), Mei, Luis and Sara (LOW) with resolved due dates. *A 4B model sometimes gets a relative date off by a few days.* |

**Point out:** the JSON schema is generated from a Java record and sent as `response_format`, which LM Studio enforces with grammar-constrained decoding. Bean Validation checks what the schema can't.

## 7 · MCP server + client (about 3 min)

1. The **Discovered from the server** panel lists the tools `convert_currency`, `world_time` and `public_holidays`, the resource `travel://fx-rates`, and the prompt `trip-briefing`. They are discovered at runtime and not hard-coded.
2. Click **Tokyo trip**. The trace shows three **remote** tool calls (mode `mcp`). Expected answer: Dec 24 is *not* a public holiday in Japan, the current Tokyo time, and 500 USD ≈ 75,231 JPY.
3. Click **Trip briefing**. The app fetches the server's prompt template (shown under *Prompt fetched from the MCP server*), then runs it with the tools.
4. Optionally run `claude mcp add --transport http acme-travel-desk http://localhost:8081/mcp` and ask Claude the Tokyo question: same server, different client.

Stop `mcp-server` and ask again: you get a clean HTTP 503 with a hint, and the rest of the app keeps working.

## 8 · Observability (about 2 min)

1. Run a few demos first.
2. **8 Observability → Refresh live counters** shows requests, average and max latency, and input/output tokens per feature, plus guardrail interventions.
3. **Open "Acme AI Overview" dashboard** shows:
   - tokens per minute by feature
   - p95 latency by feature
   - tool calls by tool name
   - guardrail events
   - tokens by model
   - model-call versus vector-search latency
   - request outcomes per feature (success, blocked, approval pending, error)
   - semantic cache hit rate, tokens saved and lookups (demo 11)
   - recent traces
4. Click a trace (for example `http post /api/helpdesk/chat`). The span tree shows *HTTP → ChatClient → advisors → `acme helpdesk` → model calls and `execute_tool` spans*. For the parallel agent, the four model calls overlap.

**Point out:** prompt and completion text is kept out of traces by default (PII). One property enables it for debugging.

## 9 · LLM evaluation (about 3 min, runs about 1–2 min)

1. **Run quality gate (default settings)**: 11 golden questions. Each is graded on facts (deterministic), relevancy and groundedness (LLM-as-judge). Expected: **QUALITY GATE PASSED**, about 80–90%.
2. **Run with bad retrieval settings** (top-k 1, threshold 0.95): retrieval starves, answers become "not found", and the result is **QUALITY GATE FAILED** at about 10%.
3. The CI version of the same check:

   ```bash
   ./mvnw verify -Pllm-eval
   ```

   It prints the scorecard in the console and fails the build below 80%.

**Point out:** the evaluators are Spring AI's `RelevancyEvaluator` and `FactCheckingEvaluator`. A small "verdict normalizer" advisor makes them robust with chatty small models.

## 10 · Text-to-SQL (about 3 min)

**Story:** IT managers ask the helpdesk database questions in plain English, and nothing they type can change or leak data.

| Try button | Expected result |
|---|---|
| **Open tickets per priority** | A count per priority, the generated `GROUP BY` query, guard pills (`single SELECT`, `tables allowed: [ticket]`, `added LIMIT 200`) and a one-line answer. |
| **Out-of-warranty laptops** | LAP-1877 (Alice Smith), LAP-1502 (Miguel Garcia), LAP-1950 (Olu Adeyemi), via a join on `employee` with `warranty_until < CURRENT_DATE`. |
| **Tickets per department (7 days)** | Counts per department from a `ticket` ⋈ `employee` join, using today's date. |
| **Blocked: delete** | Red **BLOCKED · sql-guard**, rule `not-a-select`, with the `DELETE` the model wrote. HTTP 400; the database never sees it. |
| **Blocked: other table** | Rule `table-not-allowed` for `spring_ai_chat_memory`. Reading it wouldn't harm the database, but it would expose other people's chats. |

Open **The schema description the model receives** to show the business definitions that come from Postgres `COMMENT`s.

**Point out:** three independent layers: the prompt asks for a SELECT, the JSqlParser guard enforces it on the parsed query, and the read-only transaction would refuse a write anyway (`TextToSqlIT` proves the last one with the guard bypassed).

## 11 · Semantic caching (about 2 min)

**Story:** employees ask the same policy questions in different words. Repeats come from the cache: no retrieval, no model call, no tokens.

1. Click **Run the paraphrase test**. It clears the cache, then:
   - asks *"How many leave days can I carry over to next year?"* for real: **cache miss**, about 4–5 s;
   - asks it again: **served from semantic cache**, similarity 1.000, about 0.1 s, with the saved time and tokens;
   - probes five rewordings without calling the model.
2. At the default threshold **0.95**: *carry forward* (0.97) and *carry over into* (0.99) hit; *vacation days* (0.91, same meaning) misses; *take next year* (0.94, a different question) correctly misses.
3. Drag the **What if the threshold were** slider to **0.90**: *vacation days* turns into a hit, but *take next year* turns red, **WRONG answer served**. That is the trade-off behind the default.
4. On **3 RAG with citations**, ask the leave question again: the answer shows **served from semantic cache**. Reloading the sample handbook clears the cache, and the next ask is a miss again.

**Point out:** only the stateless RAG feature is cached; entries are separated by category filter, retrieval settings and model, and expire after 24 h. Grafana shows the hit rate and tokens saved.

## 12 · Multi-agent with A2A (about 4 min)

**Story:** buying hardware is the procurement team's job, and they have their own AI agent. The helpdesk agent asks it over the A2A protocol instead of doing it itself.

Start `procurement-agent` first (port 8082). Its budgets live in memory, so restart it to reset them before a demo.

1. **Agent card (discovered)** shows what ai-app read from `http://localhost:8082/.well-known/agent-card.json`: name, endpoint, JSON-RPC transport, and the `hardware-purchase` skill with examples.
2. Click **Replacement laptop**. The task comes back **completed** with a timeline (*submitted → Searching the catalog → Checking the Engineering budget for 1 x LAP-STD-14 = 1350 EUR → Raised PR-31xx*) and two artifacts: a text summary and the purchase request as data. Open the JSON-RPC request and response to show the wire format.
3. **Developer laptop (finance approval)**: the agent picks the Dell Precision for someone who runs local AI models; 2450 EUR is over the 2000 EUR limit, so the PR is `PENDING_FINANCE_APPROVAL`.
4. **Over budget**: 3 laptops for HR cost 4050 EUR with 1500 EUR left. Nothing is raised, and the summary gives the numbers.
5. **Missing department (input-required)**: the task stops in **input-required** in under a second, asking which department pays. Click **Answer** ("Marketing pays"): the *same task* continues and completes.
6. **Full flow:** click **Report LAP-1502 broken and ask for a replacement**. The helpdesk agent (demo 4) proposes `requestHardwarePurchase` and waits for approval. Click **Approve**: the helpdesk tool calls the procurement agent over A2A, the PR number comes back into the helpdesk agent's answer, and the procurement task appears below it.

Stop `procurement-agent` and send a message: the page shows HTTP 503 with a hint to start it.

**Point out:** the helpdesk agent never sees the procurement agent's prompt, tools or model, only the card and the task. And the facts other agents rely on (which department pays, the total, the budget check, the PR number) are enforced in code, because the 4B model got each of them wrong at least once while this demo was built.
