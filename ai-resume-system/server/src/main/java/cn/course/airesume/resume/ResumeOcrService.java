package cn.course.airesume.resume;

import cn.course.airesume.analysis.AiClient;
import cn.course.airesume.analysis.AiProperties;
import cn.course.airesume.common.BizException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
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
 * 限制：最多识别 {@code ai.ocr-max-pages} 页（默认 3），单页图片超过 3MB 时自动缩放，
 * 未配置密钥或关闭开关时给出明确提示而不是静默失败。
 */
@Service
public class ResumeOcrService {
    private static final Logger log = LoggerFactory.getLogger(ResumeOcrService.class);
    /** 单页图片上限，避免 base64 后请求体过大被网关拒绝 */
    private static final long MAX_PAGE_BYTES = 3L * 1024 * 1024;

    private final AiClient aiClient;
    private final AiProperties props;

    public ResumeOcrService(AiClient aiClient, AiProperties props) {
        this.aiClient = aiClient;
        this.props = props;
    }

    public boolean isAvailable() {
        return props.isOcrUsable();
    }

    public String unavailableMessage() {
        if (!props.isOcrEnabled()) {
            return "扫描件识别已关闭（ai.ocr-enabled=false），请上传可复制文字的 PDF/DOCX 或手动填写";
        }
        return "扫描件识别需要视觉模型：请以 ai 配置启动并设置 AI_API_KEY，或上传文字版文件";
    }

    /** 图片简历（PNG/JPG）直接识别。 */
    public String recognizeImage(byte[] imageBytes) {
        if (!isAvailable()) throw new BizException(unavailableMessage());
        String text = callModel(imageBytes);
        if (text.isBlank()) {
            throw new BizException("图片中未识别到文字，请确认图片清晰，或改用手动填写");
        }
        return text;
    }

    /** 扫描件 PDF：逐页渲染后识别，最多 ai.ocr-max-pages 页。 */
    public String recognizePdf(byte[] pdfBytes) {
        if (!isAvailable()) throw new BizException(unavailableMessage());
        int pages = Math.max(1, props.getOcrMaxPages());
        int dpi = Math.max(72, props.getOcrDpi());
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            if (doc.isEncrypted()) throw new BizException("PDF 已加密，无法解析，请上传未加密文件");
            int total = Math.min(pages, doc.getNumberOfPages());
            PDFRenderer renderer = new PDFRenderer(doc);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < total; i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                String pageText = callModel(toPng(img));
                if (!pageText.isBlank()) sb.append(pageText).append('\n');
                img.flush();
            }
            if (sb.toString().trim().isEmpty()) {
                throw new BizException("扫描件未识别到文字，请确认文件清晰度或改用手动填写");
            }
            return sb.toString().trim();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("扫描件识别失败：{}", e.getMessage());
            throw new BizException("扫描件识别失败，请稍后重试或改用手动填写");
        }
    }

    private String callModel(byte[] pngBytes) {
        try {
            return aiClient.ocrImage(pngBytes, props.getOcrModel());
        } catch (AiClient.AiException e) {
            log.warn("OCR 调用失败：{}", e.getMessage());
            throw new BizException("OCR 识别失败：" + e.getMessage());
        }
    }

    /** 编码为 PNG；超过单页上限时按比例缩小后重编码。 */
    private byte[] toPng(BufferedImage img) throws Exception {
        byte[] png = encode(img);
        double scale = 0.65;
        while (png.length > MAX_PAGE_BYTES && scale > 0.2) {
            png = encode(scale(img, scale));
            scale -= 0.15;
        }
        return png;
    }

    private byte[] encode(BufferedImage img) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
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
}
