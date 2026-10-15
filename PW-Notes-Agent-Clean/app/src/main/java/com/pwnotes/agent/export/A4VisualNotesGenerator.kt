package com.pwnotes.agent.export

import android.content.Context
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.File

class A4VisualNotesGenerator(private val context: Context) {

    fun exportHtmlToPdf(
        htmlContent: String,
        outputFileName: String = "PW_Study_Notes_${System.currentTimeMillis()}.pdf",
        onComplete: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val webView = WebView(context)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                val printAdapter = webView.createPrintDocumentAdapter("PW_Notes_Doc")
                val printAttributes = PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()

                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val outputFile = File(downloadsDir, outputFileName)

                try {
                    val fileDescriptor = ParcelFileDescriptor.open(
                        outputFile,
                        ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_WRITE_ONLY
                    )

                    printAdapter.onLayout(
                        null,
                        printAttributes,
                        CancellationSignal(),
                        object : PrintDocumentAdapter.LayoutResultCallback() {
                            override fun onLayoutFinished(info: android.print.PrintDocumentInfo?, changed: Boolean) {
                                printAdapter.onWrite(
                                    arrayOf(PageRange.ALL_PAGES),
                                    fileDescriptor,
                                    CancellationSignal(),
                                    object : PrintDocumentAdapter.WriteResultCallback() {
                                        override fun onWriteFinished(pages: Array<out PageRange>?) {
                                            fileDescriptor.close()
                                            onComplete(outputFile)
                                        }

                                        override fun onWriteFailed(error: CharSequence?) {
                                            fileDescriptor.close()
                                            onError(Exception("PDF writing failed: $error"))
                                        }
                                    }
                                )
                            }
                        },
                        null
                    )
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }

        webView.loadDataWithBaseURL(null, htmlContent, "text/html", "UTF-8", null)
    }
}
