package org.example.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFFooter;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFSDT;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 将各类文档抽取为纯文本，供 RAG 分片与向量化使用。
 */
@Service
public class DocumentTextExtractionService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentTextExtractionService.class);

    public String extractPlainText(Path path) throws IOException {
        String lower = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".markdown")) {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        if (lower.endsWith(".doc")) {
            return extractDoc(path);
        }
        if (lower.endsWith(".docx")) {
            return extractDocx(path);
        }
        if (lower.endsWith(".pdf")) {
            return extractPdf(path);
        }
        throw new IllegalArgumentException("不支持的文档格式（无法抽取文本）: " + path.getFileName());
    }

    private String extractDoc(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             HWPFDocument doc = new HWPFDocument(in)) {
            WordExtractor extractor = new WordExtractor(doc);
            String text = extractor.getText();
            if (text == null) {
                return "";
            }
            text = text.trim();
            if (text.isEmpty()) {
                logger.warn("DOC 未抽取到文本: {}", path);
            }
            return text;
        }
    }

    private String extractDocx(Path path) throws IOException {
        try (InputStream in = Files.newInputStream(path);
             XWPFWordExtractor extractor = new XWPFWordExtractor(new XWPFDocument(in))) {
            String text = extractor.getText();
            if (text != null) {
                text = text.trim();
            }
            if (text != null && !text.isBlank()) {
                return text;
            }
        } catch (org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException e) {
            throw new IOException("不是有效的 Office Open XML（.docx）文件", e);
        }
        // 抽取器无正文时：按 body 元素顺序、页眉页脚、SDT 控件、Run 深层文本兜底（部分模板/论文版式仅在此处可读出）
        try (InputStream in = Files.newInputStream(path);
             XWPFDocument doc = new XWPFDocument(in)) {
            String structured = extractDocxStructured(doc).trim();
            // POI 仅抽到极短串时（常见为版式/域导致），再尝试 Tika，往往能得到完整正文
            if (structured.length() >= 120) {
                return structured;
            }
            String tika = extractWithTika(path);
            if (tika != null && !tika.isBlank()) {
                String tk = tika.trim();
                if (structured.isBlank() || tk.length() > structured.length() + 40) {
                    logger.info("DOCX 采用 Tika 抽取（POI 为空或过短）: poi={} 字符, tika={} 字符",
                            structured.length(), tk.length());
                    return tk;
                }
            }
            if (!structured.isBlank()) {
                return structured;
            }
            int bodyEl = doc.getBodyElements() != null ? doc.getBodyElements().size() : 0;
            int hdr = doc.getHeaderList() != null ? doc.getHeaderList().size() : 0;
            int ftr = doc.getFooterList() != null ? doc.getFooterList().size() : 0;
            String title = "";
            try {
                if (doc.getProperties() != null && doc.getProperties().getCoreProperties() != null) {
                    String t = doc.getProperties().getCoreProperties().getTitle();
                    title = t != null ? t : "";
                }
            } catch (Exception ignored) {
            }
            logger.warn("DOCX 与 Tika 均未得到有效文本: {} (bodyElements={}, headers={}, footers={}, coreTitle={})",
                    path.getFileName(), bodyEl, hdr, ftr, title.isBlank() ? "(无)" : title);
            return "";
        } catch (org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException e) {
            throw new IOException("不是有效的 Office Open XML（.docx）文件", e);
        }
    }

    /**
     * 结构化抽取：正文按 {@link XWPFDocument#getBodyElements()} 顺序（段落/表格/SDT 等与 Word 显示顺序更一致），
     * 并合并页眉、页脚；段落优先 {@link XWPFParagraph#getText()}，为空时再拼各 {@link XWPFRun} 文本（部分样式下 getText 为空但 Run 有字）。
     */
    private static String extractDocxStructured(XWPFDocument doc) {
        StringBuilder sb = new StringBuilder();
        for (XWPFHeader h : doc.getHeaderList()) {
            appendPartBodyElements(h.getBodyElements(), sb);
        }
        appendPartBodyElements(doc.getBodyElements(), sb);
        for (XWPFFooter f : doc.getFooterList()) {
            appendPartBodyElements(f.getBodyElements(), sb);
        }
        return sb.toString().trim();
    }

    private static void appendPartBodyElements(java.util.List<IBodyElement> elements, StringBuilder sb) {
        if (elements == null) {
            return;
        }
        for (IBodyElement el : elements) {
            if (el instanceof XWPFParagraph p) {
                appendParagraphDeep(p, sb);
            } else if (el instanceof XWPFTable table) {
                appendTable(table, sb);
            } else if (el instanceof XWPFSDT sdt) {
                try {
                    if (sdt.getContent() != null) {
                        String t = sdt.getContent().getText();
                        if (t != null && !t.isBlank()) {
                            sb.append(t.trim()).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void appendParagraphDeep(XWPFParagraph p, StringBuilder sb) {
        String t = p.getText();
        if (t != null && !t.isBlank()) {
            sb.append(t.trim()).append('\n');
            return;
        }
        StringBuilder runs = new StringBuilder();
        for (XWPFRun run : p.getRuns()) {
            String rt = run.getText(0);
            if (rt != null && !rt.isEmpty()) {
                runs.append(rt);
            }
        }
        String merged = runs.toString().trim();
        if (!merged.isEmpty()) {
            sb.append(merged).append('\n');
        }
    }

    private static void appendTable(XWPFTable table, StringBuilder sb) {
        for (XWPFTableRow row : table.getRows()) {
            for (XWPFTableCell cell : row.getTableCells()) {
                appendPartBodyElements(cell.getBodyElements(), sb);
                sb.append(' ');
            }
            sb.append('\n');
        }
    }

    /**
     * Apache Tika：对部分仅 OOXML、文本框或复杂样式下 POI 抽不到的 Word 更有效。
     */
    private String extractWithTika(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            AutoDetectParser parser = new AutoDetectParser();
            // 单文件上限约 32MB 文本，避免极端文档撑爆内存
            BodyContentHandler handler = new BodyContentHandler(32 * 1024 * 1024);
            Metadata meta = new Metadata();
            parser.parse(in, handler, meta, new ParseContext());
            return handler.toString();
        } catch (IOException | SAXException | TikaException e) {
            logger.warn("Tika 抽取失败 {}: {}", path.getFileName(), e.getMessage());
            return "";
        }
    }

    private String extractPdf(Path path) throws IOException {
        try (PDDocument doc = PDDocument.load(path.toFile())) {
            StringBuilder head = new StringBuilder();
            appendPdfDocumentInformation(doc, head);

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setLineSeparator("\n");
            stripper.setSuppressDuplicateOverlappingText(true);
            stripper.setWordSeparator(" ");
            String body = stripper.getText(doc);
            if (body == null) {
                body = "";
            }
            body = body.trim();
            if (body.isEmpty()) {
                logger.warn("PDF 正文层无文本（扫描件/纯图片等）: {}", path);
            }
            String combined = (head + body).trim();
            if (combined.isEmpty()) {
                logger.warn("PDF 元数据与正文均为空: {}", path);
            }
            return combined;
        }
    }

    /**
     * 将 PDF 文件属性（作者、标题等）置于正文前，便于 RAG；与正文层互补。
     */
    private static void appendPdfDocumentInformation(PDDocument doc, StringBuilder sb) {
        try {
            PDDocumentInformation info = doc.getDocumentInformation();
            if (info == null) {
                return;
            }
            if (info.getTitle() != null && !info.getTitle().isBlank()) {
                sb.append("[PDF标题: ").append(info.getTitle().trim()).append("]\n");
            }
            if (info.getAuthor() != null && !info.getAuthor().isBlank()) {
                sb.append("[PDF作者: ").append(info.getAuthor().trim()).append("]\n");
            }
            if (info.getSubject() != null && !info.getSubject().isBlank()) {
                sb.append("[PDF主题: ").append(info.getSubject().trim()).append("]\n");
            }
            if (info.getKeywords() != null && !info.getKeywords().isBlank()) {
                sb.append("[PDF关键词: ").append(info.getKeywords().trim()).append("]\n");
            }
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
        } catch (Exception ignored) {
            // 加密或损坏的元数据区不影响正文抽取
        }
    }
}
