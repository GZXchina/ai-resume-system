package cn.course.airesume.resume;

import cn.course.airesume.common.BizException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.SAXParserFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/**
 * 简历文件导入（FR-RESUME-04/05）：把 PDF / DOCX / DOC / TXT / 图片中的文字解析成结构化草稿。
 * 只做「提取 + 启发式识别」，不直接入库；识别不确定的字段通过 warnings 交给学生确认。
 * 安全约束：扩展名白名单、校验文件头、限制 5MB 与解压后大小、XML 禁用 DTD/外部实体。
 * 扫描件与图片走多模态 OCR（{@link ResumeOcrService}），文字版文件优先用本地解析。
 */
@Service
public class ResumeImportService {
    private static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    private static final int MAX_TEXT_CHARS = 20000;
    private static final int MAX_PREVIEW_CHARS = 600;
    private static final int MAX_DOCX_XML_BYTES = 8 * 1024 * 1024;

    private final ResumeOcrService ocrService;

    public ResumeImportService(ResumeOcrService ocrService) {
        this.ocrService = ocrService;
    }

    /** 文本抽取结果：note 非空时补充到 warnings（如 OCR 提示）。 */
    private record Extracted(String text, String note) {}

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(1[3-9]\\d{9})(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern TARGET = Pattern.compile("(?:求职意向|应聘岗位|目标岗位|意向岗位)\\s*[:：]?\\s*(.{2,50})");
    private static final Pattern PERIOD = Pattern.compile(
            "(20\\d{2}\\s*[.\\-/年]\\s*\\d{1,2}\\s*(?:[-~至]\\s*(?:20\\d{2}\\s*[.\\-/年]\\s*\\d{1,2}|至今))?)");

    /** 常见技能词典：按长度倒序匹配，命中后遮蔽原文，避免 Spring 与 Spring Boot 重复计数。 */
    private static final List<String> SKILL_DICT = List.of(
            "Spring Boot", "Spring Cloud", "MyBatis", "PostgreSQL", "Elasticsearch", "RabbitMQ",
            "Kubernetes", "JavaScript", "TypeScript", "数据结构", "计算机网络", "操作系统",
            "微服务", "分布式", "并发", "算法", "Java", "Python", "Redis", "MySQL", "MongoDB",
            "Kafka", "Docker", "Linux", "Maven", "Git", "JVM", "Vue", "React", "Node.js",
            "Django", "Flask", "Go", "C++", "C#", "PHP", "HTML", "CSS", "Nginx", "Spring");

    private static final List<String> SECTION_HEADS = List.of(
            "教育经历", "教育背景", "项目经历", "项目经验", "实习经历", "工作经历", "实践经历",
            "技能清单", "专业技能", "技能特长", "荣誉奖项", "获奖情况", "自我评价", "个人评价");

    public static class Draft {
        public String personName = "";
        public String target = "";
        public String phone = "";
        public String email = "";
        public String summary = "";
        public List<String> skills = new ArrayList<>();
        public List<Map<String, String>> projects = new ArrayList<>();
    }

    public Map<String, Object> parse(MultipartFile file) {
        byte[] bytes = readBytes(file);
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().trim();
        String ext = extensionOf(fileName);
        // 文件头纠错：Word「另存为」时偶有扩展名与实际格式不符（.doc 实为 docx 或相反）
        if (("doc".equals(ext) || "docx".equals(ext)) && bytes.length > 8) {
            boolean ole2 = isOle2(bytes);
            boolean zip = bytes[0] == 'P' && bytes[1] == 'K';
            if (ole2 && "docx".equals(ext)) ext = "doc";
            else if (zip && "doc".equals(ext)) ext = "docx";
        }
        if (!Set.of("pdf", "docx", "doc", "txt", "png", "jpg", "jpeg").contains(ext)) {
            throw new BizException("仅支持 PDF、DOCX、DOC、TXT 与图片（PNG/JPG）格式的简历文件");
        }

        Extracted extracted = switch (ext) {
            case "pdf" -> extractPdf(bytes);
            case "docx" -> extractDocx(bytes);
            case "doc" -> extractDoc(bytes);
            case "png", "jpg", "jpeg" -> extractImage(bytes, ext);
            default -> new Extracted(decodeText(bytes), null);
        };
        String text = normalize(extracted.text());
        if (text.length() < 20) {
            throw new BizException("未能从文件中提取到有效文字，请确认文件内容或改用手动填写");
        }
        boolean truncated = text.length() > MAX_TEXT_CHARS;
        if (truncated) text = text.substring(0, MAX_TEXT_CHARS);

        List<String> warnings = new ArrayList<>();
        Draft draft = buildDraft(text, warnings);
        warnings.add(0, "导入结果为草稿：请逐项核对姓名、求职意向、联系方式后再保存");
        warnings.add("教育经历不会自动识别，请在表单中补充");
        if (extracted.note() != null) warnings.add(0, extracted.note());
        if (truncated) warnings.add("简历文字超过 2 万字，仅解析前 2 万字");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fileName", fileName);
        data.put("fileType", ext);
        data.put("textLength", text.length());
        data.put("personName", draft.personName);
        data.put("target", draft.target);
        data.put("phone", draft.phone);
        data.put("email", draft.email);
        data.put("summary", draft.summary);
        data.put("skills", draft.skills);
        data.put("projects", draft.projects);
        data.put("warnings", warnings);
        data.put("textPreview", text.substring(0, Math.min(MAX_PREVIEW_CHARS, text.length())));
        return data;
    }

    /* ---------------- 文件读取与校验 ---------------- */

    private byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new BizException("请选择要导入的文件");
        if (file.getSize() > MAX_FILE_BYTES) throw new BizException("文件不能超过 5MB");
        try (InputStream in = file.getInputStream()) {
            byte[] bytes = in.readAllBytes();
            if (bytes.length > MAX_FILE_BYTES) throw new BizException("文件不能超过 5MB");
            return bytes;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("文件读取失败，请重新选择");
        }
    }

    private static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private Extracted extractPdf(byte[] bytes) {
        if (bytes.length < 4 || bytes[0] != '%' || bytes[1] != 'P' || bytes[2] != 'D' || bytes[3] != 'F') {
            throw new BizException("文件不是有效的 PDF，请检查后重试");
        }
        String text;
        try (PDDocument doc = PDDocument.load(bytes)) {
            if (doc.isEncrypted()) throw new BizException("PDF 已加密，无法解析，请上传未加密文件");
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setEndPage(Math.min(10, doc.getNumberOfPages()));
            text = stripper.getText(doc);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("PDF 解析失败，请确认文件未损坏后重试");
        }
        if (normalize(text).length() >= 20) return new Extracted(text, null);

        // 无文字层：按扫描件走 OCR 回退
        if (!ocrService.isAvailable()) {
            throw new BizException("PDF 未提取到文字，可能是扫描件。" + ocrService.unavailableMessage());
        }
        String ocr = ocrService.recognizePdf(bytes);
        return new Extracted(ocr, "本文件为扫描件，已通过 OCR 识别，请重点核对文字准确性");
    }

    private Extracted extractDocx(byte[] bytes) {
        if (bytes.length < 2 || bytes[0] != 'P' || bytes[1] != 'K') {
            throw new BizException("文件不是有效的 DOCX，请检查后重试");
        }
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!"word/document.xml".equals(entry.getName())) continue;
                byte[] xml = zip.readNBytes(MAX_DOCX_XML_BYTES + 1);
                if (xml.length > MAX_DOCX_XML_BYTES) throw new BizException("文档内容过大，无法解析");
                return new Extracted(parseDocumentXml(xml), null);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("DOCX 解析失败，请确认文件未损坏后重试");
        }
        throw new BizException("不是有效的 DOCX（缺少正文），请另存为 .docx 后重新上传");
    }

    /** Word 97-2003 二进制格式（.doc）：POI HWPF 抽取正文。 */
    private Extracted extractDoc(byte[] bytes) {
        if (!isOle2(bytes)) {
            throw new BizException("文件不是有效的 DOC（Word 97-2003），请检查后重试");
        }
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes))) {
            String text = new WordExtractor(doc).getText();
            return new Extracted(text, null);
        } catch (EncryptedDocumentException e) {
            throw new BizException("DOC 已加密，无法解析，请解除密码后重试");
        } catch (Exception e) {
            throw new BizException("DOC 解析失败，请确认文件未损坏，或另存为 .docx 后重试");
        }
    }

    /** 图片简历（PNG/JPG）：交给多模态模型 OCR。 */
    private Extracted extractImage(byte[] bytes, String ext) {
        boolean png = "png".equals(ext);
        if (png && !isPng(bytes)) throw new BizException("文件不是有效的 PNG 图片，请检查后重试");
        if (!png && !isJpeg(bytes)) throw new BizException("文件不是有效的 JPG 图片，请检查后重试");
        String text = ocrService.recognizeImage(bytes);
        return new Extracted(text, "本文件为图片简历，已通过 OCR 识别，请重点核对姓名、联系方式与技能");
    }

    private static boolean isOle2(byte[] b) {
        return b.length > 4 && (b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF
                && (b[2] & 0xFF) == 0x11 && (b[3] & 0xFF) == 0xE0;
    }

    private static boolean isPng(byte[] b) {
        return b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static boolean isJpeg(byte[] b) {
        return b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF;
    }

    /** 从 word/document.xml 提取段落文本，禁用 DTD 与外部实体。 */
    private String parseDocumentXml(byte[] xml) throws Exception {
        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        StringBuilder out = new StringBuilder();
        factory.newSAXParser().parse(new ByteArrayInputStream(xml), new DefaultHandler() {
            private boolean inText = false;

            @Override
            public void startElement(String uri, String localName, String qName, Attributes attributes) {
                if ("t".equals(localName)) inText = true;
                if ("tab".equals(localName)) out.append(' ');
            }

            @Override
            public void characters(char[] ch, int start, int length) {
                if (inText) out.append(ch, start, length);
            }

            @Override
            public void endElement(String uri, String localName, String qName) {
                if ("t".equals(localName)) inText = false;
                if ("p".equals(localName)) out.append('\n');
            }
        });
        String text = out.toString();
        if (normalize(text).length() < 20) throw new BizException("DOCX 未提取到有效文字，请检查文档内容");
        return text;
    }

    /** TXT 优先 UTF-8，失败回退 GBK（兼容 Windows 记事本保存的中文文本）。 */
    private String decodeText(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            text = new String(bytes, Charset.forName("GBK"));
        }
        if (text.indexOf('\uFFFD') >= 0) text = new String(bytes, Charset.forName("GBK"));
        return text;
    }

    /* ---------------- 文本 → 草稿 ---------------- */

    private Draft buildDraft(String text, List<String> warnings) {
        Draft d = new Draft();
        List<String> lines = Arrays.stream(text.split("\n"))
                .map(String::trim).filter(s -> !s.isEmpty()).limit(400).toList();

        Matcher pm = PHONE.matcher(text);
        if (pm.find()) d.phone = pm.group(1);
        Matcher em = EMAIL.matcher(text);
        if (em.find()) d.email = em.group();

        for (String line : lines) {
            Matcher tm = TARGET.matcher(line);
            if (tm.find()) {
                String t = tm.group(1).trim();
                t = t.split("(电话|手机|邮箱|联系方式)")[0].trim();
                d.target = t.length() > 50 ? t.substring(0, 50) : t;
                break;
            }
        }
        d.personName = guessName(lines);
        d.summary = guessSummary(lines);
        d.skills = guessSkills(text);
        d.projects = guessProjects(lines);

        if (d.personName.isBlank()) warnings.add("未识别到姓名，请手动填写");
        if (d.target.isBlank()) warnings.add("未识别到求职意向，请手动填写");
        if (d.phone.isBlank() && d.email.isBlank()) warnings.add("未识别到手机号或邮箱，请手动填写");
        if (d.skills.isEmpty()) warnings.add("未识别到技能关键词，请手动补充");
        if (d.projects.isEmpty()) warnings.add("未识别到项目/实习经历，请手动补充");
        return d;
    }

    private String guessName(List<String> lines) {
        for (String line : lines.subList(0, Math.min(8, lines.size()))) {
            String s = line.replaceAll("\\s+", "").replaceFirst("^(姓名|名字)[:：]?", "");
            if (s.length() < 2 || s.length() > 20) continue;
            if (s.matches(".*(简历|求职|个人|联系|电话|邮箱|@|http|意向|应聘|技能|教育|项目|经历|简介|评价|证书).*")) continue;
            if (s.matches(".*\\d.*")) continue;
            return s;
        }
        return "";
    }

    private String guessSummary(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (isSectionHead(line)) break;
            if (line.matches(".*(简历|求职意向|应聘岗位|目标岗位).*")) continue;
            if (line.matches(".*(1[3-9]\\d{9}|@|20\\d{2}).*")) continue;
            if (line.length() < 6) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(line);
            if (sb.length() >= 160) break;
        }
        String s = sb.toString().trim();
        if (s.length() > 200) s = s.substring(0, 200);
        return s;
    }

    private List<String> guessSkills(String text) {
        String masked = text;
        List<String> hits = new ArrayList<>();
        List<String> dict = new ArrayList<>(SKILL_DICT);
        dict.sort(Comparator.comparingInt(String::length).reversed());
        for (String skill : dict) {
            Pattern p = Pattern.compile("(?i)(?<![A-Za-z0-9])" + Pattern.quote(skill) + "(?![A-Za-z0-9])");
            Matcher m = p.matcher(masked);
            boolean found = false;
            while (m.find()) {
                found = true;
                masked = masked.substring(0, m.start()) + " ".repeat(m.end() - m.start()) + masked.substring(m.end());
                m = p.matcher(masked);
            }
            if (found) hits.add(skill);
        }
        // “技能：Java、Spring Boot、MySQL” 中词典未覆盖的词也保留
        Matcher km = Pattern.compile("(?:技能清单|专业技能|技能特长|技能)\\s*[:：]\\s*([^\\n]{2,200})").matcher(text);
        if (km.find()) {
            for (String part : km.group(1).split("[、,，;；/|]")) {
                String s = part.trim();
                if (s.length() >= 2 && s.length() <= 30 && hits.stream().noneMatch(h -> h.equalsIgnoreCase(s))) {
                    hits.add(s);
                }
                if (hits.size() >= 15) break;
            }
        }
        // 去掉被更长技能名包含的短词（如同时命中 Spring Boot 与 Spring 时只保留前者）
        return hits.stream()
                .filter(h -> hits.stream().noneMatch(o -> !o.equals(h)
                        && o.toLowerCase(Locale.ROOT).contains(h.toLowerCase(Locale.ROOT))))
                .limit(15).toList();
    }

    private List<Map<String, String>> guessProjects(List<String> lines) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.matches(".*(项目经历|项目经验|实践经历).*") && l.length() <= 20) { start = i + 1; break; }
        }
        List<Map<String, String>> projects = new ArrayList<>();
        if (start < 0) return projects;

        String[] title = {""};
        StringBuilder desc = new StringBuilder();
        for (int i = start; i < lines.size() && projects.size() < 3; i++) {
            String line = lines.get(i);
            if (isSectionHead(line)) {
                flushProject(projects, title, desc);
                break;
            }
            boolean dateLine = line.matches("^20\\d{2}.*") || line.matches("^\\d{4}[.\\-/年].*");
            boolean bullet = line.matches("^[·•\\-*].*");
            boolean sentence = line.endsWith("。") || line.endsWith("；") || line.endsWith(";");
            boolean looksTitle = !dateLine && !bullet && !sentence && line.length() <= 30;
            if (looksTitle && !title[0].isBlank() && desc.length() > 0) {
                flushProject(projects, title, desc);
                if (projects.size() >= 3) break;
            }
            if (title[0].isBlank()) {
                title[0] = line;
            } else {
                desc.append(line).append(' ');
            }
        }
        if (projects.size() < 3) flushProject(projects, title, desc);
        return projects;
    }

    private void flushProject(List<Map<String, String>> projects, String[] title, StringBuilder desc) {
        if (title[0].isBlank()) return;
        projects.add(project(title[0], desc.toString()));
        title[0] = "";
        desc.setLength(0);
    }

    private Map<String, String> project(String title, String desc) {
        Map<String, String> m = new LinkedHashMap<>();
        String text = desc.trim();
        String period = "";
        Matcher pm = PERIOD.matcher(text);
        if (pm.find()) {
            period = pm.group(1).trim();
            if (text.startsWith(period)) text = text.substring(period.length()).trim();
        }
        if (text.length() > 1000) text = text.substring(0, 1000);
        m.put("title", title.length() > 80 ? title.substring(0, 80) : title);
        m.put("role", "");
        m.put("period", period);
        m.put("desc", text);
        return m;
    }

    private boolean isSectionHead(String line) {
        return line.length() <= 8 && SECTION_HEADS.stream().anyMatch(line::contains);
    }

    private static String normalize(String text) {
        if (text == null) return "";
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replace('\u00A0', ' ').replace('\u3000', ' ')
                .replaceAll("[ \\t]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
