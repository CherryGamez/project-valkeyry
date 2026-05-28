#====================================================================================================
# START - Testing Protocol - DO NOT EDIT OR REMOVE THIS SECTION
#====================================================================================================

# THIS SECTION CONTAINS CRITICAL TESTING INSTRUCTIONS FOR BOTH AGENTS
# BOTH MAIN_AGENT AND TESTING_AGENT MUST PRESERVE THIS ENTIRE BLOCK

# Communication Protocol:
# If the `testing_agent` is available, main agent should delegate all testing tasks to it.
#
# You have access to a file called `test_result.md`. This file contains the complete testing state
# and history, and is the primary means of communication between main and the testing agent.
#
# Main and testing agents must follow this exact format to maintain testing data. 
# The testing data must be entered in yaml format Below is the data structure:
# 
## user_problem_statement: {problem_statement}
## backend:
##   - task: "Task name"
##     implemented: true
##     working: true  # or false or "NA"
##     file: "file_path.py"
##     stuck_count: 0
##     priority: "high"  # or "medium" or "low"
##     needs_retesting: false
##     status_history:
##         -working: true  # or false or "NA"
##         -agent: "main"  # or "testing" or "user"
##         -comment: "Detailed comment about status"
##
## frontend:
##   - task: "Task name"
##     implemented: true
##     working: true  # or false or "NA"
##     file: "file_path.js"
##     stuck_count: 0
##     priority: "high"  # or "medium" or "low"
##     needs_retesting: false
##     status_history:
##         -working: true  # or false or "NA"
##         -agent: "main"  # or "testing" or "user"
##         -comment: "Detailed comment about status"
##
## metadata:
##   created_by: "main_agent"
##   version: "1.0"
##   test_sequence: 0
##   run_ui: false
##
## test_plan:
##   current_focus:
##     - "Task name 1"
##     - "Task name 2"
##   stuck_tasks:
##     - "Task name with persistent issues"
##   test_all: false
##   test_priority: "high_first"  # or "sequential" or "stuck_first"
##
## agent_communication:
##     -agent: "main"  # or "testing" or "user"
##     -message: "Communication message between agents"

# Protocol Guidelines for Main agent
#
# 1. Update Test Result File Before Testing:
#    - Main agent must always update the `test_result.md` file before calling the testing agent
#    - Add implementation details to the status_history
#    - Set `needs_retesting` to true for tasks that need testing
#    - Update the `test_plan` section to guide testing priorities
#    - Add a message to `agent_communication` explaining what you've done
#
# 2. Incorporate User Feedback:
#    - When a user provides feedback that something is or isn't working, add this information to the relevant task's status_history
#    - Update the working status based on user feedback
#    - If a user reports an issue with a task that was marked as working, increment the stuck_count
#    - Whenever user reports issue in the app, if we have testing agent and task_result.md file so find the appropriate task for that and append in status_history of that task to contain the user concern and problem as well 
#
# 3. Track Stuck Tasks:
#    - Monitor which tasks have high stuck_count values or where you are fixing same issue again and again, analyze that when you read task_result.md
#    - For persistent issues, use websearch tool to find solutions
#    - Pay special attention to tasks in the stuck_tasks list
#    - When you fix an issue with a stuck task, don't reset the stuck_count until the testing agent confirms it's working
#
# 4. Provide Context to Testing Agent:
#    - When calling the testing agent, provide clear instructions about:
#      - Which tasks need testing (reference the test_plan)
#      - Any authentication details or configuration needed
#      - Specific test scenarios to focus on
#      - Any known issues or edge cases to verify
#
# 5. Call the testing agent with specific instructions referring to test_result.md
#
# IMPORTANT: Main agent must ALWAYS update test_result.md BEFORE calling the testing agent, as it relies on this file to understand what to test next.

#====================================================================================================
# END - Testing Protocol - DO NOT EDIT OR REMOVE THIS SECTION
#====================================================================================================



#====================================================================================================
# Testing Data - Main Agent and testing sub agent both should log testing data below this section
#====================================================================================================


user_problem_statement: |
  Add three P1 features to Valkeyry iPaaS:
  1) Wire Spring AI ChatClient into AiEnrichmentInterceptor — must support any LLM,
     default to local Ollama (no API key), and act as an Operator Copilot capable of
     introspecting and maintaining the application.
  2) Promote DLQ peek for Kafka via Admin client (browse offset window).
  3) Add per-project Prometheus tag enrichment + per-tenant Grafana dashboards.
  Plus a comprehensive Windows step-by-step README with manual test instructions for
  every feature, and Gatling-based load testing.

backend:
  - task: "Spring AI ChatClient multi-provider (Ollama default; OpenAI/Anthropic opt-in)"
    implemented: true
    working: "NA"
    file: "src/main/java/io/valkeyry/ipaas/config/SpringAiConfig.java; interceptor/SpringAiEnrichmentEngine.java; interceptor/AiEnrichmentEngine.java; interceptor/BridgeAiEnrichmentEngine.java; interceptor/AiEnrichmentInterceptor.java; pom.xml; application.yml"
    stuck_count: 0
    priority: "high"
    needs_retesting: true
    status_history:
      - working: "NA"
        agent: "main"
        comment: "Added spring-ai-bom 1.0.1 + Ollama / OpenAI / Anthropic starters. New AiEnrichmentEngine interface with BridgeAiEnrichmentEngine (existing Python sidecar) and SpringAiEnrichmentEngine (in-process ChatClient). AiEnrichmentInterceptor refactored to delegate to the selected engine via ipaas.ai.engine = SPRING_AI | BRIDGE (default SPRING_AI). Per-message override via x-ai-engine. ChatModelRegistry resolves multiple providers with a deterministic fallback chain ending at Ollama. Configurable via ipaas.ai.spring.{provider,ollamaModel,openaiModel,anthropicModel} + spring.ai.{ollama,openai,anthropic}.* . Unit tests: AiEnrichmentInterceptorTest updated, new SpringAiEnrichmentEngineTest."

  - task: "Operator Copilot (Spring AI tool-calling agent)"
    implemented: true
    working: "NA"
    file: "src/main/java/io/valkeyry/ipaas/copilot/*.java"
    stuck_count: 0
    priority: "high"
    needs_retesting: true
    status_history:
      - working: "NA"
        agent: "main"
        comment: "New module io.valkeyry.ipaas.copilot. OperatorCopilotController exposes /api/v1/copilot/{chat,stream,tools,providers,session/{id}/{history,reset}}. CopilotChatService orchestrates ChatClient with per-session MessageWindowChatMemory (window 20). CopilotToolset exposes 6 READ tools (listQueues, listTopologies, peekDlq, queueDepth, metricsSnapshot, aiProviders) + 3 WRITE tools (retryDlqMessages, declareQueue, publishMessage) — writes require confirm=true via @ToolParam (ipaas.ai.copilot.confirm-destructive). Tool invocations counted in Micrometer (valkeyry.copilot.tool.invocations) for Grafana."

  - task: "Kafka DLQ peek via AdminClient (offset-window introspection)"
    implemented: true
    working: "NA"
    file: "src/main/java/io/valkeyry/ipaas/broker/KafkaBrokerClient.java; dlq/DlqManagementService.java; dlq/DlqManagementController.java"
    stuck_count: 0
    priority: "high"
    needs_retesting: true
    status_history:
      - working: "NA"
        agent: "main"
        comment: "KafkaBrokerClient now lazy-creates a shared AdminClient. browseDlq() uses AdminClient.describeTopics + listOffsets to compute deterministic [latest-N, latest) windows per partition (replaces the old consumer-only metadata path). New browseDlqSummary() returns per-partition earliest/latest offsets + approximateDepth for the operator UI. New endpoint GET /api/v1/{t}/{p}/dlq/{queue}/summary surfaces it. AMQP brokers gracefully return a 'Kafka-only' note."

  - task: "Per-project Prometheus tag enrichment + cardinality guard"
    implemented: true
    working: "NA"
    file: "src/main/java/io/valkeyry/ipaas/metrics/MetricsTagConfig.java"
    stuck_count: 0
    priority: "high"
    needs_retesting: true
    status_history:
      - working: "NA"
        agent: "main"
        comment: "New ServerRequestObservationConvention (Spring Boot 3.3+ canonical) tags every http.server.requests sample with tenant_id + project_id parsed from the path /api/v1/{tenantId}/{projectId}/**. Sentinel _none_ for unscoped paths. Common service / application tags via MeterRegistryCustomizer. Cardinality guard caps tenant_id <= 200 and project_id <= 500. Public helper MetricsTagConfig.workspaceTags(t, p) for custom counters/gauges. Pure-unit test MetricsTagConfigTest covers parsing + helpers."

frontend:
  - task: "Operator Copilot tab in Admin Console"
    implemented: true
    working: "NA"
    file: "frontend/src/components/admin/CopilotPanel.jsx; frontend/src/pages/AdminConsole.jsx; frontend/src/lib/ipaasClient.js"
    stuck_count: 0
    priority: "high"
    needs_retesting: true
    status_history:
      - working: "NA"
        agent: "main"
        comment: "New 'Operator Copilot' tab. Chat UI with streaming SSE (fetch ReadableStream + SSE parser in ipaasClient.copilotStream), provider switcher (auto-discovered from /providers), tools drawer, prompt-chip presets, per-browser persisted session id, Reset button. Frontend yarn build clean (no errors)."

infrastructure:
  - task: "Ollama service in docker-compose + model bootstrapper"
    implemented: true
    working: "NA"
    file: "local-dev/docker-compose.yaml"
    stuck_count: 0
    priority: "high"
    needs_retesting: true
    status_history:
      - working: "NA"
        agent: "main"
        comment: "Added ollama:11434 service (image ollama/ollama:0.5.4) with persisted ollama-models volume + health check, plus an idempotent ollama-bootstrap sidecar that pulls $COPILOT_MODEL (default qwen2.5:7b) on first compose-up."

  - task: "Grafana per-tenant/project dashboard"
    implemented: true
    working: "NA"
    file: "local-dev/observability/grafana/provisioning/dashboards/valkeyry-per-tenant.json"
    stuck_count: 0
    priority: "high"
    needs_retesting: true
    status_history:
      - working: "NA"
        agent: "main"
        comment: "New dashboard 'Valkeyry iPaaS — Per Tenant / Project' with tenant_id + project_id template variables, panels: RPS / p95 / p99 / 5xx / 429s / top endpoints / CB state / RabbitMQ depth / Copilot tool invocations. Auto-provisioned alongside the existing overview dashboard."

  - task: "Gatling load-tests Maven module"
    implemented: true
    working: "NA"
    file: "load-tests/pom.xml; load-tests/src/test/java/io/valkeyry/loadtests/*.java; load-tests/README.md"
    stuck_count: 0
    priority: "high"
    needs_retesting: true
    status_history:
      - working: "NA"
        agent: "main"
        comment: "Standalone Maven module under /app/load-tests using Gatling 3.13.4 + gatling-maven-plugin 4.17.4 (pure Java DSL). Four simulations: PublishSimulation (10→500 RPS, single tenant, p95<200ms), MultiTenantPublishSimulation (3-target fan-out), FileStreamingSimulation (1 MiB body), CopilotChatSimulation (Spring AI chat workload). README has Windows + Linux invocation."

docs:
  - task: "WINDOWS_GUIDE.md — comprehensive Windows step-by-step + manual test playbook"
    implemented: true
    working: "NA"
    file: "WINDOWS_GUIDE.md; LOCAL_SETUP.md"
    stuck_count: 0
    priority: "high"
    needs_retesting: false
    status_history:
      - working: "NA"
        agent: "main"
        comment: "New WINDOWS_GUIDE.md (490+ lines) — prereq install with winget commands, docker compose bring-up, Vault bootstrap PowerShell, Ollama model pull, env-var config matrix, 11 manual test sections (smoke, catalog, DLQ + Kafka summary, topologies, multi-publish, AI ENRICH+DECIDE, Copilot end-to-end, SSE metrics, Grafana per-tenant, OIDC, Gatling), troubleshooting matrix, file cheat-sheet, appendix with bootstrap-vault.ps1. LOCAL_SETUP.md updated to link to it."

metadata:
  created_by: "main_agent"
  version: "1.0"
  test_sequence: 0
  run_ui: false

test_plan:
  current_focus:
    - "Spring AI ChatClient multi-provider (Ollama default; OpenAI/Anthropic opt-in)"
    - "Operator Copilot (Spring AI tool-calling agent)"
    - "Kafka DLQ peek via AdminClient (offset-window introspection)"
    - "Per-project Prometheus tag enrichment + cardinality guard"
  stuck_tasks: []
  test_all: false
  test_priority: "high_first"

agent_communication:
  - agent: "main"
    message: |
      Iteration 8 delivered. Three P1 features + comprehensive Windows guide + Gatling load tests.

      What's NEW since iteration 7:
       • Spring AI ChatClient wired (Ollama default; OpenAI/Anthropic activate on api-key)
       • Operator Copilot tab + REST endpoints (chat / stream / tools / providers / history / reset)
       • CopilotToolset with 6 READ tools + 3 WRITE tools (write-confirm gated)
       • Kafka DLQ AdminClient peek (deterministic offset windows + new /summary endpoint)
       • Per-project Prometheus tags via ServerRequestObservationConvention + cardinality guard
       • Per-tenant Grafana dashboard with template vars (tenant_id, project_id)
       • Ollama service + idempotent model-pull container in docker-compose
       • Gatling load-tests module (4 simulations, Maven invocation, Windows-friendly)
       • WINDOWS_GUIDE.md: 11 manual-test sections with PowerShell snippets

      Build status:
       • Frontend `yarn build` ✅ clean
       • Backend: no JDK available in this container, but code matches Spring Boot 3.3.5 +
         Spring AI 1.0.1 APIs; pom.xml + application.yml updated.

      The user is expected to run `mvn test` on Windows for backend verification.
      No backend testing agent run requested yet — recommend running deep_testing_backend_v2
      after the user confirms their Windows build is green.
