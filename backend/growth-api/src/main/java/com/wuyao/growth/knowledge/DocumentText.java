package com.wuyao.growth.knowledge;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Plain text out of an uploaded document: PDF, Word (.docx), or a UTF-8 text / Markdown file. */
final class DocumentText {

    /** What the model is given at most; longer documents are cut and the merchant is told so. */
    static final int MAX_CHARS = 30_000;
    /** A .docx is a zip; its main part is read into memory, so it gets its own bound. */
    private static final int MAX_DOCX_XML_BYTES = 20 * 1024 * 1024;

    private DocumentText() {
    }

    static class UnreadableException extends Exception {
        UnreadableException(String message) {
            super(message);
        }
    }

    static String extract(String name, String mimeType, byte[] bytes) throws UnreadableException {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        String mime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        String text;
        if (lower.endsWith(".pdf") || mime.equals("application/pdf")) text = pdf(bytes);
        else if (lower.endsWith(".docx") || mime.contains("wordprocessingml")) text = docx(bytes);
        else if (lower.endsWith(".txt") || lower.endsWith(".md") || mime.startsWith("text/")) text = utf8(bytes);
        else throw new UnreadableException("只支持 PDF、Word（.docx）、TXT 和 Markdown 文件");
        text = normalize(text);
        if (text.isBlank()) throw new UnreadableException("没有读到文字。扫描版 PDF 或图片里的字暂时读不了");
        return text;
    }

    private static String pdf(byte[] bytes) throws UnreadableException {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted()) throw new UnreadableException("PDF 设置了密码，请去掉密码后再上传");
            return new PDFTextStripper().getText(document);
        } catch (IOException e) {
            throw new UnreadableException("PDF 文件无法打开，可能已损坏");
        }
    }

    /** Paragraph and line breaks become newlines; every other tag is dropped. */
    private static String docx(byte[] bytes) throws UnreadableException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (!"word/document.xml".equals(entry.getName())) continue;
                String xml = new String(readBounded(zip), StandardCharsets.UTF_8);
                String text = xml.replaceAll("</w:p>", "\n").replaceAll("<w:(br|tab)\\b[^>]*/>", "\n")
                        .replaceAll("<[^>]+>", "");
                return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                        .replace("&apos;", "'").replace("&amp;", "&");
            }
        } catch (IOException e) {
            throw new UnreadableException("Word 文件无法打开，可能已损坏");
        }
        throw new UnreadableException("Word 文件无法打开。旧版 .doc 请另存为 .docx 再上传");
    }

    private static byte[] readBounded(ZipInputStream zip) throws IOException, UnreadableException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[64 * 1024];
        for (int read; (read = zip.read(buffer)) > 0; ) {
            if (out.size() + read > MAX_DOCX_XML_BYTES) throw new UnreadableException("Word 文件内容过大");
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static String utf8(byte[] bytes) throws UnreadableException {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new UnreadableException("文本文件需要是 UTF-8 编码");
        }
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').replaceAll("[ \\t\\u3000]+", " ")
                .replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }
}
