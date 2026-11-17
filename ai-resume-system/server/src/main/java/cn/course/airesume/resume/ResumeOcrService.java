package cn.course.airesume.resume;

import cn.course.airesume.analysis.AiClient;
import cn.course.airesume.analysis.AiProperties;
import cn.course.airesume.common.BizException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 扫描件识别（FR-RESUME-05）：把无文字层的 PDF / 图片简历交给多模态模型做 OCR。
 *
 * <p>实现选择：不引入本地 Tesseract（需额外安装与中文语言包），
 * 复用已接入的 OpenAI 兼容网关，用支持图片输入的模型识别中文。
 *
 * <p>健壮性约定：
 * <ul>
 *   <li>图片先按最长边与体积归一化，PNG 超限自动转 JPEG，再超限才降采样，降采样会给出提示；</li>
 *   <li>扫描件逐页识别，单页失败不丢弃已成功的页，只标注失败页号；</li>
 *   <li>页数上限、DPI 均做上下限钳制，避免配置错误导致内存溢出或超长耗时；</li>
 *   <li>附加说明通过 {@link OcrResult#notes()} 返回，由导入服务合并进 warnings。</li>
 * </ul>
 */
@Service
public class ResumeOcrService {
    private static final Logger log = LoggerFactory.getLogger(ResumeOcrService.class);

    /** 单页图片上限，避免 base64 后请求体过大被网关拒绝 */
    private static final long MAX_PAGE_BYTES = 3L * 1024 * 1024;
    /** 单边像素上限，兼顾清晰度与请求体积 */
    private static final int MAX_EDGE_PX = 2400;
    /** DPI 钳制区间：过低无法识别，过高易 OOM */
    private static final int MIN_DPI = 96;
    private static final int MAX_DPI = 300;
    /** 页数硬上限，防止配置成 100 页时长时间占用线程 */
    private static final int MAX_PAGES_CAP = 20;
    /** JPEG 压缩质量 */
    private static final float JPEG_QUALITY = 0.9f;

    private final AiClient aiClient;
    private final AiProperties props;

    public ResumeOcrService(AiClient aiClient, AiProperties props) {
        this.aiClient = aiClient;
        this.props = props;
    }

    /** 识别结果：正文 + 需要提示给用户的附加说明（页数截断、部分失败、压缩等）。 */
    public record OcrResult(String text, List<String> notes) {}

    /** 归一化后的待发送图片。degraded=true 表示因体积超限做过降采样。 */
    private record Prepared(byte[] data, String mime, boolean degraded) {}

    public boolean isAvailable() {
        return props.isOcrUsable();
    }

    public String unavailableMessage() {
        if (!props.isOcrEnabled()) {
            return "扫描件识别已关闭（ai.ocr-enabled=false），请上传可复制文字的 PDF/DOCX 或手动填写";
        }
        return "扫描件识别需要视觉模型：请以 ai 配置启动并设置 AI_API_KEY，或上传文字版文件";
    }

    /** 图片简历（PNG/JPG）直接识别。mime 必须与文件真实格式一致。 */
    public OcrResult recognizeImage(byte[] imageBytes, String mime) {
        if (!isAvailable()) throw new BizException(unavailableMessage());
        BufferedImage img = decode(imageBytes);
        try {
            Prepared prepared = prepare(img);
            String text = callModel(prepared);
            if (text.isBlank()) {
                throw new BizException("图片中未识别到文字，请确认图片清晰，或改用手动填写");
            }
            List<String> notes = new ArrayList<>();
            if (prepared.degraded()) {
                notes.add("图片分辨率过高，已自动压缩后识别，复杂排版可能不准，请重点核对");
            }
            return new OcrResult(text, notes);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("图片识别失败：{}", e.getMessage());
            throw new BizException("图片识别失败，请稍后重试或改用手动填写");
        } finally {
            img.flush();
        }
    }

    /** 扫描件 PDF：逐页渲染后识别，最多 ai.ocr-max-pages 页（已钳制）。 */
    public OcrResult recognizePdf(byte[] pdfBytes) {
        if (!isAvailable()) throw new BizException(unavailableMessage());
        int pages = clamp(props.getOcrMaxPages(), 1, MAX_PAGES_CAP);
        int dpi = clamp(props.getOcrDpi(), MIN_DPI, MAX_DPI);
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            if (doc.isEncrypted()) throw new BizException("PDF 已加密，无法解析，请上传未加密文件");
            int total = doc.getNumberOfPages();
            int usePages = Math.min(pages, total);
            PDFRenderer renderer = new PDFRenderer(doc);
            StringBuilder sb = new StringBuilder();
            List<Integer> failedPages = new ArrayList<>();
            boolean degraded = false;
            String firstError = null;

            for (int i = 0; i < usePages; i++) {
                BufferedImage img = null;
                try {
                    img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                    Prepared prepared = prepare(img);
                    degraded |= prepared.degraded();
                    String pageText = callModel(prepared);
                    if (!pageText.isBlank()) sb.append(pageText).append('\n');
                } catch (Exception e) {
                    // 单页失败不中断整体：记录页号，继续识别后续页
                    failedPages.add(i + 1);
                    if (firstError == null) firstError = e.getMessage();
                    log.warn("扫描件第 {} 页识别失败：{}", i + 1, e.getMessage());
                } finally {
                    if (img != null) img.flush();
                }
            }

            if (sb.toString().trim().isEmpty()) {
                throw new BizException(failedPages.isEmpty()
                        ? "扫描件未识别到文字，请确认文件清晰度或改用手动填写"
                        : "扫描件识别失败：" + (firstError == null ? "请稍后重试" : firstError));
            }

            List<String> notes = new ArrayList<>();
            if (usePages < total) {
                notes.add("本文件共 " + total + " 页，按配置仅识别前 " + usePages + " 页，后续页面未导入");
            }
            if (!failedPages.isEmpty()) {
                notes.add("第 " + joinPages(failedPages) + " 页识别失败，其余页面已导入，请手动补全");
            }
            if (degraded) {
                notes.add("部分页面分辨率过高，已自动压缩后识别，复杂排版可能不准，请重点核对");
            }
            return new OcrResult(sb.toString().trim(), notes);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("扫描件识别失败：{}", e.getMessage());
            throw new BizException("扫描件识别失败，请稍后重试或改用手动填写");
        }
    }

    /* ---------------- 内部实现 ---------------- */

    private String callModel(Prepared prepared) {
        try {
            return aiClient.ocrImage(prepared.data(), prepared.mime(), props.getOcrModel());
        } catch (AiClient.AiException e) {
            log.warn("OCR 调用失败：{}", e.getMessage());
            throw new BizException("OCR 识别失败：" + e.getMessage());
        }
    }

    private BufferedImage decode(byte[] bytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            if (img == null) throw new BizException("图片无法解码，请确认文件未损坏，或换一张 PNG/JPG 图片");
            return img;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException("图片无法解码，请确认文件未损坏，或换一张 PNG/JPG 图片");
        }
    }

    /**
     * 归一化：单边超限先等比缩小；PNG 超出体积上限转 JPEG；仍超限才逐级降采样。
     * degraded 表示发生了降采样（可能影响识别准确率）。
     */
    private Prepared prepare(BufferedImage src) throws Exception {
        BufferedImage img = limitEdge(src);
        byte[] png = encodePng(img);
        if (png.length <= MAX_PAGE_BYTES) return new Prepared(png, "image/png", false);

        byte[] jpg = encodeJpeg(img);
        if (jpg.length <= MAX_PAGE_BYTES) return new Prepared(jpg, "image/jpeg", false);

        double scale = 0.8;
        Prepared last = new Prepared(jpg, "image/jpeg", true);
        while (scale > 0.25) {
            BufferedImage small = scale(img, scale);
            byte[] data = encodeJpeg(small);
            small.flush();
            last = new Prepared(data, "image/jpeg", true);
            if (data.length <= MAX_PAGE_BYTES) return last;
            scale -= 0.15;
        }
        return last;
    }

    /** 单边不超过 MAX_EDGE_PX，超出则等比缩小。 */
    private BufferedImage limitEdge(BufferedImage src) {
        int max = Math.max(src.getWidth(), src.getHeight());
        if (max <= MAX_EDGE_PX) return src;
        return scale(src, (double) MAX_EDGE_PX / max);
    }

    private byte[] encodePng(BufferedImage img) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    /** JPEG 不支持透明通道，先铺白底转 RGB。 */
    private byte[] encodeJpeg(BufferedImage img) throws Exception {
        BufferedImage rgb = img;
        if (img.getType() != BufferedImage.TYPE_INT_RGB) {
            rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, img.getWidth(), img.getHeight());
            g.drawImage(img, 0, 0, null);
            g.dispose();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(JPEG_QUALITY);
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    private BufferedImage scale(BufferedImage src, double scale) {
        int w = Math.max(1, (int) (src.getWidth() * scale));
        int h = Math.max(1, (int) (src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String joinPages(List<Integer> pages) {
        StringBuilder sb = new StringBuilder();
        for (Integer p : pages) {
            if (sb.length() > 0) sb.append('、');
            sb.append(p);
        }
        return sb.toString();
    }
}
