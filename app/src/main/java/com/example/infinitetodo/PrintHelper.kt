package com.example.infinitetodo

import android.content.Context
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.text.SimpleDateFormat
import java.util.*

object PrintHelper {

    fun printTasks(
        context: Context,
        jobName: String,
        tasks: List<TaskItem>,
        checklistsMap: Map<Long, List<ChecklistItem>> = emptyMap(),
        attachmentsMap: Map<Long, List<RichAttachment>> = emptyMap()
    ) {
        val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
        val html = buildString {
            append("<!DOCTYPE html><html><head><meta charset='UTF-8'>")
            append("<style>")
            append("body { font-family: sans-serif; margin: 20px; line-height: 1.4; color: #333; }")
            append("h1 { color: #1B5E20; border-bottom: 2px solid #1B5E20; padding-bottom: 6px; }")
            append(".task-card { border: 1px solid #ccc; border-radius: 6px; padding: 12px; margin-bottom: 12px; page-break-inside: avoid; }")
            append(".task-title { font-size: 16px; font-weight: bold; margin-bottom: 4px; }")
            append(".meta { font-size: 11px; color: #666; margin-bottom: 6px; }")
            append(".priority { display: inline-block; padding: 2px 6px; border-radius: 4px; color: white; font-size: 10px; font-weight: bold; }")
            append(".p-LOW { background: #689F38; } .p-MEDIUM { background: #0288D1; } .p-HIGH { background: #F57C00; } .p-URGENT { background: #D32F2F; }")
            append(".notes { background: #f9f9f9; padding: 6px; border-left: 3px solid #ccc; margin-top: 6px; font-size: 13px; }")
            append(".checklists, .attachments { margin-top: 8px; font-size: 12px; }")
            append("ul { margin: 4px 0 0 16px; padding: 0; }")
            append("</style></head><body>")
            append("<h1>$jobName</h1>")
            append("<p class='meta'>Generated: ${dateFormat.format(Date())}</p>")

            for (task in tasks) {
                append("<div class='task-card'>")
                append("<div class='task-title'>${if (task.isCompleted) "✓ " else "○ "} ${task.title}")
                append(" <span class='priority p-${task.priority.name}'>${task.priority.name}</span></div>")

                append("<div class='meta'>")
                append("Created: ${dateFormat.format(Date(task.createdTimestamp))}")
                task.dueTimestamp?.let { append(" | Due: ${dateFormat.format(Date(it))}") }
                append("</div>")

                if (!task.notes.isNullOrBlank()) {
                    append("<div class='notes'>${task.notes.replace("\n", "<br/>")}</div>")
                }

                val checklists = checklistsMap[task.id]
                if (!checklists.isNullOrEmpty()) {
                    append("<div class='checklists'><strong>Checklist:</strong><ul>")
                    for (c in checklists) {
                        append("<li>${if (c.isDone) "[x]" else "[ ]"} ${c.text}</li>")
                    }
                    append("</ul></div>")
                }

                val attachments = attachmentsMap[task.id]
                if (!attachments.isNullOrEmpty()) {
                    append("<div class='attachments'><strong>Attachments & Contacts:</strong><ul>")
                    for (a in attachments) {
                        append("<li>[${a.type.name}] ${a.displayName}")
                        if (a.contactPhone != null) append(" (${a.contactPhone} - ${if (a.isContactPending) "Pending" else "Completed"})")
                        append("</li>")
                    }
                    append("</ul></div>")
                }

                append("</div>")
            }
            append("</body></html>")
        }

        val webView = WebView(context)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = false

            override fun onPageFinished(view: WebView?, url: String?) {
                val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
                val printAdapter = webView.createPrintDocumentAdapter(jobName)
                printManager?.print(jobName, printAdapter, PrintAttributes.Builder().build())
            }
        }
        webView.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}
