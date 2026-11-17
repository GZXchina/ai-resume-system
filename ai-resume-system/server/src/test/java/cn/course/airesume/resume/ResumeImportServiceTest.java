package cn.course.airesume.resume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.course.airesume.common.BizException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 简历导入的文本抽取与启发式识别（FR-RESUME-04/05）。
 * 不依赖 Spring 容器与数据库，OCR 服务用 Mock 替身。
 */
class ResumeImportServiceTest {

    private static final String SAMPLE = """
            张伟
            求职意向：Java 后端开发实习生
            电话：13800138000 邮箱：zhangwei@example.com
            技能：Java、Spring Boot、MySQL
            项目经历
            校园二手交易平台
            2024.03-2024.06 负责后端接口开发与数据库设计。
            """;

    private ResumeImportService service(ResumeOcrService ocr) {
        return new ResumeImportService(ocr);
    }

    private MockMultipartFile txt(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("TXT 导入：识别姓名、求职意向、电话、邮箱与技能")
    @SuppressWarnings("unchecked")
    void parseTxtExtractsCoreFields() {
        Map<String, Object> d = service(mock(ResumeOcrService.class)).parse(txt("简历.txt", SAMPLE));

        assertEquals("张伟", d.get("personName"));
        assertEquals("Java 后端开发实习生", d.get("target"));
        assertEquals("13800138000", d.get("phone"));
        assertEquals("zhangwei@example.com", d.get("email"));
        List<String> skills = (List<String>) d.get("skills");
        assertTrue(skills.contains("Java"), "应识别 Java: " + skills);
        assertTrue(skills.contains("Spring Boot"), "应识别 Spring Boot: " + skills);
        assertTrue(skills.contains("MySQL"), "应识别 MySQL: " + skills);
        assertFalse(((List<Map<String, String>>) d.get("projects")).isEmpty(), "应识别到项目经历");
    }

    @Test
    @DisplayName("TXT 导入：剥离 UTF-8 BOM，避免姓名带不可见字符")
    void stripsUtf8Bom() {
        Map<String, Object> d = service(mock(ResumeOcrService.class))
                .parse(txt("bom.txt", "\uFEFF" + SAMPLE));

        assertEquals("张伟", d.get("personName"));
    }

    @Test
    @DisplayName("导入结果始终带草稿提示")
    @SuppressWarnings("unchecked")
    void alwaysWarnsDraft() {
        Map<String, Object> d = service(mock(ResumeOcrService.class)).parse(txt("简历.txt", SAMPLE));
        List<String> warnings = (List<String>) d.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("草稿")), warnings.toString());
    }

    @Test
    @DisplayName("不支持的扩展名被拒绝")
    void rejectsUnsupportedExtension() {
        MockMultipartFile f = new MockMultipartFile("file", "a.exe", "application/octet-stream", new byte[32]);
        BizException e = assertThrows(BizException.class, () -> service(mock(ResumeOcrService.class)).parse(f));
        assertTrue(e.getMessage().contains("格式"), e.getMessage());
    }

    @Test
    @DisplayName("超过 5MB 的文件被拒绝")
    void rejectsOversizeFile() {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        MockMultipartFile f = new MockMultipartFile("file", "big.txt", "text/plain", big);
        BizException e = assertThrows(BizException.class, () -> service(mock(ResumeOcrService.class)).parse(f));
        assertTrue(e.getMessage().contains("5MB"), e.getMessage());
    }

    @Test
    @DisplayName("伪造 PNG 文件头被拒绝，且不会调用 OCR")
    void rejectsFakePngWithoutCallingOcr() {
        ResumeOcrService ocr = mock(ResumeOcrService.class);
        byte[] fake = new byte[64];
        fake[0] = (byte) 0x89;
        fake[1] = 'A';
        MockMultipartFile f = new MockMultipartFile("file", "fake.png", "image/png", fake);

        assertThrows(BizException.class, () -> service(ocr).parse(f));
        verify(ocr, never()).recognizeImage(any(), any());
    }

    @Test
    @DisplayName("JPG 图片按 image/jpeg 传给 OCR，不再误报为 PNG")
    void passesJpegMimeToOcr() {
        ResumeOcrService ocr = mock(ResumeOcrService.class);
        when(ocr.recognizeImage(any(), eq("image/jpeg")))
                .thenReturn(new ResumeOcrService.OcrResult(SAMPLE, List.of()));

        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0};
        MockMultipartFile f = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpeg);
        Map<String, Object> d = service(ocr).parse(f);

        verify(ocr).recognizeImage(any(), eq("image/jpeg"));
        assertEquals("张伟", d.get("personName"));
    }

    @Test
    @DisplayName("PNG 的 MIME 为 image/png")
    void passesPngMimeToOcr() {
        ResumeOcrService ocr = mock(ResumeOcrService.class);
        when(ocr.recognizeImage(any(), eq("image/png")))
                .thenReturn(new ResumeOcrService.OcrResult(SAMPLE, List.of("附加提示")));

        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
        MockMultipartFile f = new MockMultipartFile("file", "photo.png", "image/png", png);
        @SuppressWarnings("unchecked")
        Map<String, Object> d = service(ocr).parse(f);

        verify(ocr).recognizeImage(any(), eq("image/png"));
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) d.get("warnings");
        assertTrue(warnings.contains("附加提示"), "OCR 附加提示应合并进 warnings: " + warnings);
    }
}
