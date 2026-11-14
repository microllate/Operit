package com.ai.assistance.operit.core.config

import com.ai.assistance.operit.core.chat.hooks.PromptHookContext
import com.ai.assistance.operit.core.chat.hooks.PromptHookRegistry
import com.ai.assistance.operit.data.model.SystemToolPromptCategory
import com.ai.assistance.operit.data.model.ToolPrompt
import com.ai.assistance.operit.data.model.ToolParameterSchema

/**
 * 系统工具提示词管理器
 * 包含所有工具的结构化定义
 */
object SystemToolPrompts {

    private fun buildSafBookmarksSectionEn(safBookmarkNames: List<String>): String {
        val names = safBookmarkNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (names.isEmpty()) return ""
        val listed = names.joinToString(", ") { "repo:$it" }
        return """

**Attached Local Storage Repository:**
- environment (optional): you can also use `environment="repo:<repositoryName>"` to operate in an attached local storage repository.
- Paths are absolute (e.g., `/`, `/work/index.html`).
- Available repositories: $listed
""".trimEnd()
    }

    private fun buildSafBookmarksSectionCn(safBookmarkNames: List<String>): String {
        val names = safBookmarkNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
        if (names.isEmpty()) return ""
        val listed = names.joinToString("、") { "repo:$it" }
        return """

**附加本地储存仓库：**
- environment（可选）：也可以使用 `environment="repo:<仓库名>"` 在附加本地储存仓库中操作。
- 路径使用绝对路径（例如 `/`、`/work/index.html`）。
- 当前可用仓库：$listed
""".trimEnd()
    }
    
    // ==================== 基础工具 ====================
    val basicTools = SystemToolPromptCategory(
        categoryName = "Available tools",
        tools = listOf(
            ToolPrompt(
                name = "execute_shell",
                description = "Execute Android shell commands directly using configured Root/Shizuku permissions, without activating super_admin. Use execute_hidden_terminal_command for Linux workspace commands.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "command", type = "string", description = "Android shell command", required = true)
                )
            ),
            ToolPrompt(
                name = "execute_hidden_terminal_command",
                description = "Preferred first choice for Linux workspace commands, file operations, script editing, and code search. Runs directly in the built-in background terminal; do not activate super_admin for these tasks. Use super_admin:terminal only when this tool cannot meet the task requirements.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "command", type = "string", description = "Command to execute", required = true),
                    ToolParameterSchema(name = "executor_key", type = "string", description = "Optional terminal context key", required = false, default = "default"),
                    ToolParameterSchema(name = "timeout_ms", type = "integer", description = "Timeout in milliseconds", required = false, default = "120000")
                )
            ),
            ToolPrompt(
                name = "use_package",
                description = "Activate a package only when its specific capabilities are needed. Do not activate super_admin solely for Linux file operations or code search; prefer execute_hidden_terminal_command. For Android shell commands prefer execute_shell.",
                parametersStructured = listOf(
                    ToolParameterSchema(
                        name = "package_name",
                        type = "string",
                        description = "name of the package to activate",
                        required = true
                    )
                )
            )
        )
    )
    
    val basicToolsCn = SystemToolPromptCategory(
        categoryName = "可用工具",
        tools = listOf(
            ToolPrompt(
                name = "execute_shell",
                description = "直接通过已配置的 Root/Shizuku 权限执行 Android Shell 命令，无需激活 super_admin。Linux 工作区命令使用 execute_hidden_terminal_command。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "command", type = "string", description = "Android Shell 命令", required = true)
                )
            ),
            ToolPrompt(
                name = "execute_hidden_terminal_command",
                description = "Linux 工作区命令、文件操作、脚本修改和代码搜索优先使用本工具。直接调用，无需激活 super_admin；只有本工具无法满足任务需求时才使用 super_admin:terminal。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "command", type = "string", description = "执行命令", required = true),
                    ToolParameterSchema(name = "executor_key", type = "string", description = "Optional terminal context key", required = false, default = "default"),
                    ToolParameterSchema(name = "timeout_ms", type = "integer", description = "Timeout in milliseconds", required = false, default = "120000")
                )
            ),
            ToolPrompt(
                name = "use_package",
                description = "仅在需要工具包特有能力时激活。Linux 文件操作和代码搜索优先使用 execute_hidden_terminal_command，Android Shell 优先使用 execute_shell；不要仅为执行这些命令而激活 super_admin。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "package_name", type = "string", description = "要激活的包名", required = true)
                )
            )
        )
    )
    
    // ==================== 文件系统工具 ====================
    val fileSystemTools = SystemToolPromptCategory(
        categoryName = "File System Tools",
        tools = listOf(
            ToolPrompt(
                name = "grep_context",
                description = "Search for relevant content based on intent/context understanding. Supports two modes: 1) Directory mode: when path is a directory, finds most relevant files. 2) File mode: when path is a file, finds most relevant code segments within that file. Uses semantic relevance scoring.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "directory or file path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "intent", type = "string", description = "intent or context description string", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "file filter for directory mode", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "maximum items to return", required = false, default = "10")
                )
            ),
            ToolPrompt(
                name = "download_file",
                description = "Download a file from the internet. Two modes: (1) Provide `url` + `destination`. (2) Provide `visit_key` + (`link_number` or `image_number`) + `destination` to download an item by index from a previous `visit_web` result.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "optional, file URL. If omitted, use visit_key + link_number/image_number to download from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "optional, visitKey from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "optional, 1-based link index from Results (use with visit_key)", required = false),
                    ToolParameterSchema(name = "image_number", type = "integer", description = "optional, 1-based image index from Images (use with visit_key)", required = false),
                    ToolParameterSchema(name = "destination", type = "string", description = "save path", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "optional, same as read_file environment", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "optional HTTP headers as JSON object string, e.g. {\"Referer\":\"...\"}", required = false)
                )
            )
        )
    )
    
    val fileSystemToolsCn = SystemToolPromptCategory(
        categoryName = "文件系统工具",
        tools = listOf(
            ToolPrompt(
                name = "grep_context",
                description = "语义搜索文件或代码。path 为目录时返回相关文件，为文件时返回相关代码段，按相关度排序。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "path", type = "string", description = "目录或文件路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "可选，同 read_file 的 environment", required = false),
                    ToolParameterSchema(name = "intent", type = "string", description = "意图或上下文描述字符串", required = true),
                    ToolParameterSchema(name = "file_pattern", type = "string", description = "目录模式下的文件过滤", required = false, default = "\"*\""),
                    ToolParameterSchema(name = "max_results", type = "integer", description = "返回的最大项数", required = false, default = "10")
                )
            ),
            ToolPrompt(
                name = "download_file",
                description = "下载文件。使用 url 直接下载，或通过 visit_key + link_number/image_number 下载 visit_web 返回的链接或图片（编号从1开始）。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "文件下载URL", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "上次 visit_web 的访问标识", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "Results 链接编号，从1开始", required = false),
                    ToolParameterSchema(name = "image_number", type = "integer", description = "Images 图片编号，从1开始", required = false),
                    ToolParameterSchema(name = "destination", type = "string", description = "保存路径", required = true),
                    ToolParameterSchema(name = "environment", type = "string", description = "执行环境，同 read_file", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "HTTP请求头，JSON对象字符串，例如{\"Referer\":\"...\"}", required = false)
                )
            )
        )
    )
    
    // ==================== HTTP工具 ====================
    val httpTools = SystemToolPromptCategory(
        categoryName = "HTTP Tools",
        tools = listOf(
            ToolPrompt(
                name = "visit_web",
                description = "Visit webpages and extract text, links, and optional image links. Use `url` for a new page or `visit_key` + `link_number` to follow a link (1-based). Set `include_image_links=true` to list images; download images via `download_file` with `visit_key` + `image_number`. Read-only browsing: no login, clicks, or form submissions; not suitable for raw HTTP/API responses.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "optional, webpage URL", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "optional, string, the visitKey from a previous visit_web result", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "optional, int, 1-based index of the link to follow (matches the `[n]` in Results; range 1..links.length)", required = false),
                    ToolParameterSchema(name = "include_image_links", type = "boolean", description = "optional, boolean, when true include extracted image links in the result (imageLinks)", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "optional HTTP headers as JSON object string, e.g. {\"Referer\":\"...\"}", required = false),
                    ToolParameterSchema(name = "user_agent_preset", type = "string", description = "optional, quick select user agent: desktop/android", required = false),
                    ToolParameterSchema(name = "user_agent", type = "string", description = "optional, full custom user agent override", required = false)
                )
            )
        )
    )
    
    val httpToolsCn = SystemToolPromptCategory(
        categoryName = "HTTP工具",
        tools = listOf(
            ToolPrompt(
                name = "visit_web",
                description = "访问网页并提取文本、链接及可选图片链接。传 url 访问新页面；传 visit_key + link_number 跟进链接（编号从1开始）。设置 include_image_links=true 获取图片列表；下载图片使用 download_file 的 visit_key + image_number。仅支持只读网页提取，不支持登录、点击或表单操作，也不适合获取原始 HTTP/API 响应。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "url", type = "string", description = "网页URL", required = false),
                    ToolParameterSchema(name = "visit_key", type = "string", description = "上次 visit_web 的访问标识", required = false),
                    ToolParameterSchema(name = "link_number", type = "integer", description = "Results 链接编号，从1开始", required = false),
                    ToolParameterSchema(name = "include_image_links", type = "boolean", description = "是否返回图片链接", required = false),
                    ToolParameterSchema(name = "headers", type = "string", description = "HTTP请求头，JSON对象字符串，例如{\"Referer\":\"...\"}", required = false),
                    ToolParameterSchema(name = "user_agent_preset", type = "string", description = "UA预设：desktop/android", required = false),
                    ToolParameterSchema(name = "user_agent", type = "string", description = "自定义UA，优先于预设", required = false)
                )
            )
        )
    )
    
    // ==================== 记忆库工具 ====================
    val memoryTools = SystemToolPromptCategory(
        categoryName = "Memory and Memory Library Tools",
        tools = listOf(
            ToolPrompt(
                name = "query_memory",
                description = "Searches the memory library for relevant memories and document chunks.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "string, the search query. You can pass a natural-language question, a space-separated phrase, or use `|` to separate multiple keywords, for example `network error timeout` or `network|error|timeout`. Inside a keyword, `*` acts as a fuzzy wildcard placeholder, for example `error*timeout`; use only `*` to return all memories", required = true),
                    ToolParameterSchema(name = "folder_path", type = "string", description = "optional, string, the specific folder path to search within", required = false),
                    ToolParameterSchema(name = "start_time", type = "string", description = "optional, local-time string in `YYYY-MM-DD` or `YYYY-MM-DD HH:mm` format. Filters memories by createdAt >= start_time", required = false),
                    ToolParameterSchema(name = "end_time", type = "string", description = "optional, local-time string in `YYYY-MM-DD` or `YYYY-MM-DD HH:mm` format. Filters memories by createdAt <= end_time", required = false),
                    ToolParameterSchema(name = "snapshot_id", type = "string", description = "optional, string. Omit or pass empty to create a new snapshot automatically. If you pass a non-empty snapshot_id, that exact id will be used; if it does not exist yet, it will be created and can be reused across follow-up or parallel queries to exclude memories already returned by that snapshot", required = false),
                    ToolParameterSchema(name = "threshold", type = "number", description = "optional, number >= 0. Minimum relevance score required for a memory to be returned. Defaults to 0 for query_memory", required = false, default = "0"),
                    ToolParameterSchema(name = "limit", type = "integer", description = "optional, int >= 1, maximum number of results to return. When > 20, only titles and truncated content are returned", required = false, default = "20")
                )
            ),
            ToolPrompt(
                name = "get_memory_by_title",
                description = "Retrieves a memory by exact title, including document content or selected chunks.",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "title", type = "string", description = "required, string, the exact title of the memory", required = true),
                    ToolParameterSchema(name = "workspace_path", type = "string", description = "required when title is 历史摘要: the current workspace root path, used to return only its 5 most recent summary memories", required = false),
                    ToolParameterSchema(name = "chunk_index", type = "integer", description = "optional, int, read a specific chunk by its number, e.g., 3 for the 3rd chunk", required = false),
                    ToolParameterSchema(name = "chunk_range", type = "string", description = "optional, string, read a range of chunks in \"start-end\" format, e.g., \"3-7\" for chunks 3 through 7", required = false),
                    ToolParameterSchema(name = "query", type = "string", description = "optional, string, search inside the document by natural-language question or keywords. You can pass a short question, a space-separated phrase, or use `|` to separate multiple keywords, for example `error log timeout` or `error|timeout|retry`. Inside a keyword, `*` acts as a fuzzy wildcard placeholder, for example `error*timeout`", required = false),
                    ToolParameterSchema(name = "limit", type = "integer", description = "optional, int >= 1, maximum number of document chunks to return when using query. Default 20", required = false, default = "20")
                )
            )
        ),
        categoryFooter = "\nNote: The memory library and user personality profile may be updated automatically after the current reply is finalized. If you need to manage memories immediately or update user preferences, use the appropriate tools directly."
    )
    
    val memoryToolsCn = SystemToolPromptCategory(
        categoryName = "记忆与记忆库工具",
        tools = listOf(
            ToolPrompt(
                name = "query_memory",
                description = "从记忆库中搜索相关记忆和文档分块。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "query", type = "string", description = "搜索内容；支持自然语言、空格分词、| 分隔关键词、* 模糊匹配（如 error*timeout）；单独 * 返回全部记忆", required = true),
                    ToolParameterSchema(name = "folder_path", type = "string", description = "限定搜索文件夹路径", required = false),
                    ToolParameterSchema(name = "start_time", type = "string", description = "创建时间下限，格式 YYYY-MM-DD 或 YYYY-MM-DD HH:mm", required = false),
                    ToolParameterSchema(name = "end_time", type = "string", description = "创建时间上限，格式 YYYY-MM-DD 或 YYYY-MM-DD HH:mm", required = false),
                    ToolParameterSchema(name = "snapshot_id", type = "string", description = "快照ID；留空新建，非空时使用或创建该ID；复用可排除已返回的记忆", required = false),
                    ToolParameterSchema(name = "threshold", type = "number", description = "最低相关度，≥0", required = false, default = "0"),
                    ToolParameterSchema(name = "limit", type = "integer", description = "最大返回条数，≥1；超过20条仅返回标题和截断内容", required = false, default = "20")
                )
            ),
            ToolPrompt(
                name = "get_memory_by_title",
                description = "通过精确标题检索记忆，可读取完整内容或文档分块。",
                parametersStructured = listOf(
                    ToolParameterSchema(name = "title", type = "string", description = "记忆的精确标题", required = true),
                    ToolParameterSchema(name = "workspace_path", type = "string", description = "标题为历史摘要时必填：当前工作区根路径，只返回该工作区最近5条摘要", required = false),
                    ToolParameterSchema(name = "chunk_index", type = "integer", description = "读取指定分块编号", required = false),
                    ToolParameterSchema(name = "chunk_range", type = "string", description = "分块范围，如 3-7", required = false),
                    ToolParameterSchema(name = "query", type = "string", description = "文档内搜索；支持自然语言、空格分词、| 分隔关键词、* 模糊匹配", required = false),
                    ToolParameterSchema(name = "limit", type = "integer", description = "搜索时最多返回分块数，≥1", required = false, default = "20")
                )
            )
        ),
        categoryFooter = "\n注意：记忆库和用户性格档案可能会在当前回复结束后由独立系统自动更新。如果需要立即管理记忆或更新用户偏好，请直接使用相应工具。"
    )

    private val internalToolCategoriesEn: List<SystemToolPromptCategory> = SystemToolPromptsInternal.internalToolCategoriesEn
    private val internalToolCategoriesCn: List<SystemToolPromptCategory> = SystemToolPromptsInternal.internalToolCategoriesCn
    
    /**
     * 获取所有英文工具分类
     * @param hasBackendImageRecognition 是否配置了后端识图服务（IMAGE_RECOGNITION功能）
     * @param chatModelHasDirectImage 当前聊天模型是否自带识图能力（可直接看图片）
     */
    fun getAIAllCategoriesEn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        return listOf(
            basicTools,
            fileSystemTools,
            httpTools,
            memoryTools
        )
    }

    fun getAllCategoriesEn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        return getAIAllCategoriesEn(
            hasBackendImageRecognition = hasBackendImageRecognition,
            chatModelHasDirectImage = chatModelHasDirectImage,
            hasBackendAudioRecognition = hasBackendAudioRecognition,
            hasBackendVideoRecognition = hasBackendVideoRecognition,
            chatModelHasDirectAudio = chatModelHasDirectAudio,
            chatModelHasDirectVideo = chatModelHasDirectVideo,
            safBookmarkNames = safBookmarkNames
        ) + internalToolCategoriesEn
    }
    
    /**
     * 获取所有中文工具分类
     * @param hasBackendImageRecognition 是否配置了后端识图服务（IMAGE_RECOGNITION功能）
     * @param chatModelHasDirectImage 当前聊天模型是否自带识图能力（可直接看图片）
     */
    fun getAIAllCategoriesCn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        return listOf(
            basicToolsCn,
            fileSystemToolsCn,
            httpToolsCn,
            memoryToolsCn
        )
    }

    fun getAllCategoriesCn(
        hasBackendImageRecognition: Boolean = false,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList()
    ): List<SystemToolPromptCategory> {
        return getAIAllCategoriesCn(
            hasBackendImageRecognition = hasBackendImageRecognition,
            chatModelHasDirectImage = chatModelHasDirectImage,
            hasBackendAudioRecognition = hasBackendAudioRecognition,
            hasBackendVideoRecognition = hasBackendVideoRecognition,
            chatModelHasDirectAudio = chatModelHasDirectAudio,
            chatModelHasDirectVideo = chatModelHasDirectVideo,
            safBookmarkNames = safBookmarkNames
        ) + internalToolCategoriesCn
    }

    data class ManageableToolPrompt(
        val categoryName: String,
        val name: String,
        val description: String
    )

    private fun applyToolOrder(
        categories: List<SystemToolPromptCategory>,
        toolOrder: List<String>
    ): List<SystemToolPromptCategory> {
        if (toolOrder.isEmpty()) return categories
        val orderIndex = toolOrder.withIndex().associate { (index, name) -> name to index }
        return categories.map { category ->
            val sortedTools = category.tools.sortedBy { tool ->
                orderIndex[tool.name] ?: Int.MAX_VALUE
            }
            category.copy(tools = sortedTools)
        }
    }

    private fun applyToolVisibility(
        categories: List<SystemToolPromptCategory>,
        toolVisibility: Map<String, Boolean>
    ): List<SystemToolPromptCategory> {
        if (toolVisibility.isEmpty()) return categories
        return categories.mapNotNull { category ->
            val visibleTools = category.tools.filter { tool ->
                toolVisibility[tool.name] ?: true
            }
            if (visibleTools.isEmpty()) {
                null
            } else {
                category.copy(tools = visibleTools)
            }
        }
    }

    fun getManageableToolPrompts(
        useEnglish: Boolean,
        toolOrder: List<String> = emptyList()
    ): List<ManageableToolPrompt> {
        val baseCategories = if (useEnglish) {
            listOf(basicTools, fileSystemTools, httpTools, memoryTools)
        } else {
            listOf(basicToolsCn, fileSystemToolsCn, httpToolsCn, memoryToolsCn)
        }

        val result = baseCategories
            .flatMap { category ->
                category.tools.map { tool ->
                    ManageableToolPrompt(
                        categoryName = category.categoryName,
                        name = tool.name,
                        description = tool.description
                    )
                }
            }
            .distinctBy { it.name }

        return if (toolOrder.isNotEmpty()) {
            val orderIndex = toolOrder.withIndex().associate { (index, name) -> name to index }
            result.sortedBy { manageable ->
                orderIndex[manageable.name] ?: Int.MAX_VALUE
            }
        } else {
            result
        }
    }

    fun generateMemoryToolsPromptEn(
        toolVisibility: Map<String, Boolean> = emptyMap()
    ): String {
        return applyToolVisibility(listOf(memoryTools), toolVisibility)
            .firstOrNull()
            ?.toString()
            .orEmpty()
    }

    fun generateMemoryToolsPromptCn(
        toolVisibility: Map<String, Boolean> = emptyMap()
    ): String {
        return applyToolVisibility(listOf(memoryToolsCn), toolVisibility)
            .firstOrNull()
            ?.toString()
            .orEmpty()
    }

    private fun buildToolHookPayload(
        categories: List<SystemToolPromptCategory>
    ): List<Map<String, Any?>> {
        return categories.flatMap { category ->
            category.tools.map { tool ->
                mapOf(
                    "categoryName" to category.categoryName,
                    "categoryHeader" to category.categoryHeader,
                    "categoryFooter" to category.categoryFooter,
                    "name" to tool.name,
                    "description" to tool.description,
                    "parameters" to tool.parameters,
                    "details" to tool.details,
                    "notes" to tool.notes,
                    "parametersStructured" to
                        tool.parametersStructured.orEmpty().map { parameter ->
                            mapOf(
                                "name" to parameter.name,
                                "type" to parameter.type,
                                "description" to parameter.description,
                                "required" to parameter.required,
                                "default" to parameter.default
                            )
                        }
                )
            }
        }
    }

    private fun renderToolPromptFromAvailableTools(
        availableTools: List<Map<String, Any?>>
    ): String {
        if (availableTools.isEmpty()) {
            return ""
        }
        return buildToolPromptCategories(availableTools).joinToString("\n\n") { it.toString() }
    }

    private fun buildToolPromptCategories(
        availableTools: List<Map<String, Any?>>
    ): List<SystemToolPromptCategory> {
        val categories = linkedMapOf<String, MutableToolPromptCategory>()
        availableTools.forEach { item ->
            val categoryName = item["categoryName"] as? String ?: return@forEach
            val toolName = item["name"] as? String ?: return@forEach
            val description = item["description"] as? String ?: return@forEach
            val category = categories.getOrPut(categoryName) {
                MutableToolPromptCategory(
                    categoryName = categoryName,
                    categoryHeader = item["categoryHeader"] as? String ?: "",
                    categoryFooter = item["categoryFooter"] as? String ?: ""
                )
            }
            category.tools.add(
                ToolPrompt(
                    name = toolName,
                    description = description,
                    parameters = item["parameters"] as? String ?: "",
                    parametersStructured = parseToolParameterSchemas(item["parametersStructured"]),
                    details = item["details"] as? String ?: "",
                    notes = item["notes"] as? String ?: ""
                )
            )
        }
        return categories.values.map { category ->
            SystemToolPromptCategory(
                categoryName = category.categoryName,
                categoryHeader = category.categoryHeader,
                tools = category.tools,
                categoryFooter = category.categoryFooter
            )
        }
    }

    private fun parseToolParameterSchemas(value: Any?): List<ToolParameterSchema> {
        val items = value as? List<*> ?: return emptyList()
        return items.mapNotNull { item ->
            val parameter = item as? Map<*, *> ?: return@mapNotNull null
            val name = parameter["name"] as? String ?: return@mapNotNull null
            val description = parameter["description"] as? String ?: return@mapNotNull null
            ToolParameterSchema(
                name = name,
                type = parameter["type"] as? String ?: "string",
                description = description,
                required = parameter["required"] as? Boolean ?: true,
                default = (parameter["default"] as? String) ?: parameter["default"]?.toString()
            )
        }
    }

    private data class MutableToolPromptCategory(
        val categoryName: String,
        val categoryHeader: String,
        val categoryFooter: String,
        val tools: MutableList<ToolPrompt> = mutableListOf()
    )
    
    /**
     * 生成完整的工具提示词文本（英文）
     */
    fun generateToolsPromptEn(
        chatId: String? = null,
        hasBackendImageRecognition: Boolean = false,
        includeMemoryTools: Boolean = true,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        toolVisibility: Map<String, Boolean> = emptyMap(),
        toolOrder: List<String> = emptyList(),
        hookMetadata: Map<String, Any?> = emptyMap(),
        dispatchToolPromptComposeHooks: (PromptHookContext) -> PromptHookContext = PromptHookRegistry::dispatchToolPromptComposeHooks
    ): String {
        val categories = if (includeMemoryTools) {
            getAIAllCategoriesEn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
        } else {
            getAIAllCategoriesEn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
                .filter { it.categoryName != "Memory and Memory Library Tools" }
        }
        val orderedCategories = applyToolOrder(categories, toolOrder)
        val visibleCategories = applyToolVisibility(orderedCategories, toolVisibility)
        val availableTools = buildToolHookPayload(visibleCategories)
        val beforeContext =
            dispatchToolPromptComposeHooks(
                PromptHookContext(
                    stage = "before_compose_tool_prompt",
                    chatId = chatId,
                    useEnglish = true,
                    availableTools = availableTools,
                    metadata =
                        mapOf(
                            "includeMemoryTools" to includeMemoryTools,
                            "hasBackendImageRecognition" to hasBackendImageRecognition,
                            "chatModelHasDirectImage" to chatModelHasDirectImage,
                            "hasBackendAudioRecognition" to hasBackendAudioRecognition,
                            "hasBackendVideoRecognition" to hasBackendVideoRecognition,
                            "chatModelHasDirectAudio" to chatModelHasDirectAudio,
                            "chatModelHasDirectVideo" to chatModelHasDirectVideo,
                            "safBookmarkNames" to safBookmarkNames,
                            "toolVisibility" to toolVisibility,
                            "toolOrder" to toolOrder
                        ) + hookMetadata
                )
            )
        var currentAvailableTools = beforeContext.availableTools
        var prompt = beforeContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(currentAvailableTools)
        val filterContext =
            dispatchToolPromptComposeHooks(
                beforeContext.copy(
                    stage = "filter_tool_prompt_items",
                    toolPrompt = prompt,
                    availableTools = currentAvailableTools
                )
            )
        currentAvailableTools = filterContext.availableTools
        prompt = filterContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(currentAvailableTools)
        val afterContext =
            dispatchToolPromptComposeHooks(
                filterContext.copy(
                    stage = "after_compose_tool_prompt",
                    toolPrompt = prompt,
                    availableTools = currentAvailableTools
                )
            )
        return afterContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(afterContext.availableTools)
    }
    
    /**
     * 生成完整的工具提示词文本（中文）
     */
    fun generateToolsPromptCn(
        chatId: String? = null,
        hasBackendImageRecognition: Boolean = false,
        includeMemoryTools: Boolean = true,
        chatModelHasDirectImage: Boolean = false,
        hasBackendAudioRecognition: Boolean = false,
        hasBackendVideoRecognition: Boolean = false,
        chatModelHasDirectAudio: Boolean = false,
        chatModelHasDirectVideo: Boolean = false,
        safBookmarkNames: List<String> = emptyList(),
        toolVisibility: Map<String, Boolean> = emptyMap(),
        toolOrder: List<String> = emptyList(),
        hookMetadata: Map<String, Any?> = emptyMap(),
        dispatchToolPromptComposeHooks: (PromptHookContext) -> PromptHookContext = PromptHookRegistry::dispatchToolPromptComposeHooks
    ): String {
        val categories = if (includeMemoryTools) {
            getAIAllCategoriesCn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
        } else {
            getAIAllCategoriesCn(
                hasBackendImageRecognition = hasBackendImageRecognition,
                chatModelHasDirectImage = chatModelHasDirectImage,
                hasBackendAudioRecognition = hasBackendAudioRecognition,
                hasBackendVideoRecognition = hasBackendVideoRecognition,
                chatModelHasDirectAudio = chatModelHasDirectAudio,
                chatModelHasDirectVideo = chatModelHasDirectVideo,
                safBookmarkNames = safBookmarkNames
            )
                .filter { it.categoryName != "记忆与记忆库工具" }
        }
        val orderedCategories = applyToolOrder(categories, toolOrder)
        val visibleCategories = applyToolVisibility(orderedCategories, toolVisibility)
        val availableTools = buildToolHookPayload(visibleCategories)
        val beforeContext =
            dispatchToolPromptComposeHooks(
                PromptHookContext(
                    stage = "before_compose_tool_prompt",
                    chatId = chatId,
                    useEnglish = false,
                    availableTools = availableTools,
                    metadata =
                        mapOf(
                            "includeMemoryTools" to includeMemoryTools,
                            "hasBackendImageRecognition" to hasBackendImageRecognition,
                            "chatModelHasDirectImage" to chatModelHasDirectImage,
                            "hasBackendAudioRecognition" to hasBackendAudioRecognition,
                            "hasBackendVideoRecognition" to hasBackendVideoRecognition,
                            "chatModelHasDirectAudio" to chatModelHasDirectAudio,
                            "chatModelHasDirectVideo" to chatModelHasDirectVideo,
                            "safBookmarkNames" to safBookmarkNames,
                            "toolVisibility" to toolVisibility,
                            "toolOrder" to toolOrder
                        ) + hookMetadata
                )
            )
        var currentAvailableTools = beforeContext.availableTools
        var prompt = beforeContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(currentAvailableTools)
        val filterContext =
            dispatchToolPromptComposeHooks(
                beforeContext.copy(
                    stage = "filter_tool_prompt_items",
                    toolPrompt = prompt,
                    availableTools = currentAvailableTools
                )
            )
        currentAvailableTools = filterContext.availableTools
        prompt = filterContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(currentAvailableTools)
        val afterContext =
            dispatchToolPromptComposeHooks(
                filterContext.copy(
                    stage = "after_compose_tool_prompt",
                    toolPrompt = prompt,
                    availableTools = currentAvailableTools
                )
            )
        return afterContext.toolPrompt
            ?: renderToolPromptFromAvailableTools(afterContext.availableTools)
    }
}
