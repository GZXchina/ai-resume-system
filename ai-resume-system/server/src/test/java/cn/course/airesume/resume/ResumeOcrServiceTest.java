package cn.course.airesume.resume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.course.airesume.analysis.AiClient;
import cn.course.airesume.analysis.AiProperties;
import cn.course.airesume.common.BizException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 扫描件识别（FR-RESUME-05）：开关/密钥判定、MIME 传递、页数截断提示、单页失败的容错。
 * 全部用 Mock 的 AiClient，不需要真实密钥与网关。
 */
class ResumeOcrServiceTest {

    private AiProperties props(boolean enabled, String key) {
        AiProperties p = new AiProperties();
        p.setOcrEnabled(true);
        p.setEndpoint("https://ai-gateway.example.com/v1");
        p.setEnabled(enabled);
        p.setApiKey(key);
        p.setOcrModel("test-vision-model");
        p.setOcrMaxPages(3);
        p.setOcrDpi(150);
        return p;
    }

    private byte[] png(int w, int h) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", bos);
        return bos.toByteArray();
    }

    private byte[] pdf(int pages) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            for (int i = 0; i < pages; i++) doc.addPage(new PDPage());
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }

    @Test
    @DisplayName("OCR 关闭时给出明确提示，不静默失败")
    void disabledGivesExplicitMessage() {
        AiProperties p = props(true, "test-key");
        p.setOcrEnabled(false);
        ResumeOcrService s = new ResumeOcrService(mock(AiClient.class), p);

        BizException e = assertThrows(BizException.class, () -> s.recognizeImage(new byte[10], "image/png"));
        assertTrue(e.getMessage().contains("已关闭"), e.getMessage());
    }

    @Test
    @DisplayName("缺少密钥时提示需要视觉模型")
    void missingKeyGivesExplicitMessage() {
        ResumeOcrService s = new ResumeOcrService(mock(AiClient.class), props(true, ""));
        BizException e = assertThrows(BizException.class, () -> s.recognizeImage(new byte[10], "image/png"));
        assertTrue(e.getMessage().contains("视觉模型"), e.getMessage());
    }

    @Test
    @DisplayName("图片按传入的 MIME 调用模型")
    void passesImageMime() throws Exception {
        AiClient ai = mock(AiClient.class);
        when(ai.ocrImage(any(), eq("image/png"), eq("test-vision-model"))).thenReturn("张伟 13800138000");
        ResumeOcrService s = new ResumeOcrService(ai, props(true, "test-key"));

        ResumeOcrService.OcrResult r = s.recognizeImage(png(20, 20), "image/png");

        assertEquals("张伟 13800138000", r.text());
        verify(ai).ocrImage(any(), eq("image/png"), eq("test-vision-model"));
    }

    @Test
    @DisplayName("模型返回空白文本时提示未识别到文字")
    void blankTextIsRejected() throws Exception {
        AiClient ai = mock(AiClient.class);
        when(ai.ocrImage(any(), any(), any())).thenReturn("   ");
        ResumeOcrService s = new ResumeOcrService(ai, props(true, "test-key"));

        BizException e = assertThrows(BizException.class, () -> s.recognizeImage(png(20, 20), "image/png"));
        assertTrue(e.getMessage().contains("未识别到文字"), e.getMessage());
    }

    @Test
    @DisplayName("扫描件超出页数上限时给出截断提示")
    void pdfTruncationIsReported() throws Exception {
        AiClient ai = mock(AiClient.class);
        when(ai.ocrImage(any(), any(), any())).thenReturn("第 N 页内容");
        ResumeOcrService s = new ResumeOcrService(ai, props(true, "test-key"));

        ResumeOcrService.OcrResult r = s.recognizePdf(pdf(5));

        assertTrue(r.notes().stream().anyMatch(n -> n.contains("共 5 页")), r.notes().toString());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("前 3 页")), r.notes().toString());
    }

    @Test
    @DisplayName("单页识别失败不影响其余页，并标注失败页号")
    void pdfPartialFailureKeepsOtherPages() throws Exception {
        AiClient ai = mock(AiClient.class);
        when(ai.ocrImage(any(), any(), any()))
                .thenThrow(new AiClient.AiException("模拟网关 502"))
                .thenReturn("第二页正文");
        ResumeOcrService s = new ResumeOcrService(ai, props(true, "test-key"));

        ResumeOcrService.OcrResult r = s.recognizePdf(pdf(2));

        assertEquals("第二页正文", r.text());
        assertTrue(r.notes().stream().anyMatch(n -> n.contains("第 1 页")), r.notes().toString());
    }

    @Test
    @DisplayName("所有页都失败时抛出可读错误，而不是返回空草稿")
    void pdfAllPagesFailed() throws Exception {
        AiClient ai = mock(AiClient.class);
        when(ai.ocrImage(any(), any(), any())).thenThrow(new AiClient.AiException("模拟网关不可用"));
        ResumeOcrService s = new ResumeOcrService(ai, props(true, "test-key"));

        BizException e = assertThrows(BizException.class, () -> s.recognizePdf(pdf(2)));
        assertTrue(e.getMessage().contains("扫描件识别失败"), e.getMessage());
    }

    @Test
    @DisplayName("DPI 与页数配置越界时被钳制，不会放大到不可控范围")
    void configIsClamped() throws Exception {
        AiClient ai = mock(AiClient.class);
        when(ai.ocrImage(any(), any(), any())).thenReturn("文本");
        AiProperties p = props(true, "test-key");
        p.setOcrDpi(5000);
        p.setOcrMaxPages(500);
        ResumeOcrService s = new ResumeOcrService(ai, p);

        // 不抛异常即说明 5000 DPI / 500 页被钳制到安全范围
        ResumeOcrService.OcrResult r = s.recognizePdf(pdf(1));
        assertEquals("文本", r.text());
    }
}
