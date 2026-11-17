package cn.course.airesume.resume;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.course.airesume.analysis.AnalysisDao;
import cn.course.airesume.auth.AuthContext;
import cn.course.airesume.auth.LoginUser;
import cn.course.airesume.common.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 简历删除与导出的业务规则：归属校验、关联清理、导出内容与文件名。 */
class ResumeServiceTest {

    private ResumeDao resumeDao;
    private VersionDao versionDao;
    private ResumeService service;
    private final LoginUser me = new LoginUser(1L, "linzhiyuan", "林知远", "STUDENT", "ACTIVE");

    @BeforeEach
    void setUp() {
        resumeDao = mock(ResumeDao.class);
        versionDao = mock(VersionDao.class);
        AnalysisDao analysisDao = mock(AnalysisDao.class);
        SensitiveInfoScanner scanner = mock(SensitiveInfoScanner.class);
        service = new ResumeService(resumeDao, versionDao, analysisDao, scanner, new ObjectMapper());
        AuthContext.set(me);
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    private ResumeDao.ResumeRow row(long id, long userId) {
        return new ResumeDao.ResumeRow(id, userId, "后端开发实习 · 主简历", "林知远",
                "Java 后端开发实习生", "13812345678", "linzhiyuan@example.com",
                "软件工程本科在读，熟悉 Spring Boot 与 MySQL。", 3, 92, "2026-11-17 10:00");
    }

    private void stubFullResume() {
        when(resumeDao.listSkills(anyLong())).thenReturn(List.of("Java", "Spring Boot"));
        when(resumeDao.listEducation(anyLong())).thenReturn(List.of(
                new ResumeDao.Education(0, "江南大学", "软件工程", "本科", "2022.09 - 2026.06", "3.7")));
        when(resumeDao.listProjects(anyLong())).thenReturn(List.of(
                new ResumeDao.Project(0, "校园二手交易平台", "后端负责人", "2025.03 - 2025.09", "负责订单模块。")));
    }

    /* ---------------- 导出 ---------------- */

    @Test
    @DisplayName("导出 Markdown：包含姓名、意向、联系方式、技能与项目")
    void exportMarkdownContainsAllSections() {
        when(resumeDao.findById(10L)).thenReturn(Optional.of(row(10L, me.id())));
        stubFullResume();

        Map<String, Object> out = service.export(10L, "md");
        String content = String.valueOf(out.get("content"));

        assertTrue(content.contains("# 林知远"), content);
        assertTrue(content.contains("Java 后端开发实习生"), content);
        assertTrue(content.contains("13812345678"), content);
        assertTrue(content.contains("## 专业技能"), content);
        assertTrue(content.contains("Spring Boot"), content);
        assertTrue(content.contains("## 项目 / 实习经历"), content);
        assertEquals("md", out.get("format"));
        assertTrue(String.valueOf(out.get("fileName")).endsWith(".md"), String.valueOf(out.get("fileName")));
    }

    @Test
    @DisplayName("导出纯文本：format=txt 时用不含 Markdown 标记的排版")
    void exportPlainText() {
        when(resumeDao.findById(10L)).thenReturn(Optional.of(row(10L, me.id())));
        stubFullResume();

        Map<String, Object> out = service.export(10L, "txt");
        String content = String.valueOf(out.get("content"));

        assertTrue(content.contains("【专业技能】"), content);
        assertFalse(content.contains("## "), content);
        assertEquals("txt", out.get("format"));
    }

    @Test
    @DisplayName("导出他人简历被拒绝")
    void exportOthersResumeIsRejected() {
        when(resumeDao.findById(99L)).thenReturn(Optional.of(row(99L, 2L)));
        BizException e = assertThrows(BizException.class, () -> service.export(99L, "md"));
        assertTrue(e.getMessage().contains("无权"), e.getMessage());
    }

    @Test
    @DisplayName("导出文件名去掉路径分隔符等非法字符")
    void exportFileNameIsSanitized() {
        when(resumeDao.findById(10L)).thenReturn(Optional.of(new ResumeDao.ResumeRow(
                10L, me.id(), "a/b:c", "张/三", "Java", "", "", "", 1, 50, "2026-11-17 10:00")));
        stubFullResume();

        String fileName = String.valueOf(service.export(10L, "md").get("fileName"));
        assertFalse(fileName.contains("/"), fileName);
        assertFalse(fileName.contains(":"), fileName);
    }

    /* ---------------- 删除 ---------------- */

    @Test
    @DisplayName("删除自己的简历：先清关联分析任务，再删简历")
    void deleteOwnResumeCleansTasksFirst() {
        when(resumeDao.findById(10L)).thenReturn(Optional.of(row(10L, me.id())));
        when(resumeDao.deleteAnalysesByResume(10L, me.id())).thenReturn(2);
        when(resumeDao.deleteByIdAndUser(10L, me.id())).thenReturn(1);

        Map<String, Object> out = service.deleteBatch(List.of(10L));

        assertEquals(1, out.get("deleted"));
        assertEquals(2, out.get("deletedTasks"));
        assertEquals(0, out.get("skipped"));
        verify(resumeDao).deleteAnalysesByResume(10L, me.id());
        verify(resumeDao).deleteByIdAndUser(10L, me.id());
    }

    @Test
    @DisplayName("删除他人简历被跳过，不会执行删除语句")
    void deleteOthersResumeIsSkipped() {
        when(resumeDao.findById(99L)).thenReturn(Optional.of(row(99L, 2L)));

        Map<String, Object> out = service.deleteBatch(List.of(99L));

        assertEquals(0, out.get("deleted"));
        assertEquals(1, out.get("skipped"));
        verify(resumeDao, never()).deleteByIdAndUser(anyLong(), anyLong());
        verify(resumeDao, never()).deleteAnalysesByResume(anyLong(), anyLong());
    }

    @Test
    @DisplayName("不存在的 id 计入 skipped，不影响其他 id")
    void deleteMissingResumeIsSkipped() {
        when(resumeDao.findById(10L)).thenReturn(Optional.of(row(10L, me.id())));
        when(resumeDao.findById(77L)).thenReturn(Optional.empty());
        when(resumeDao.deleteByIdAndUser(10L, me.id())).thenReturn(1);

        Map<String, Object> out = service.deleteBatch(List.of(10L, 77L));

        assertEquals(1, out.get("deleted"));
        assertEquals(1, out.get("skipped"));
    }

    @Test
    @DisplayName("空列表与超过 50 份都拒绝")
    void deleteRejectsEmptyAndTooMany() {
        assertThrows(BizException.class, () -> service.deleteBatch(List.of()));
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 51).boxed().toList();
        BizException e = assertThrows(BizException.class, () -> service.deleteBatch(tooMany));
        assertTrue(e.getMessage().contains("50"), e.getMessage());
    }

    @Test
    @DisplayName("未登录时不允许删除")
    void deleteRequiresLogin() {
        AuthContext.clear();
        assertThrows(BizException.class, () -> service.deleteBatch(List.of(10L)));
    }
}
