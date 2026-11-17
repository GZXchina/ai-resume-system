package cn.course.airesume.job;

import cn.course.airesume.auth.AuthContext;
import cn.course.airesume.auth.LoginUser;
import cn.course.airesume.common.BizException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 岗位与 JD 管理（FR-JOB-01）。校验口径：岗位名称必填 ≤50 字，JD 必填 10～5000 字。 */
@Service
public class JobService {
    private final JobDao jobDao;

    public JobService(JobDao jobDao) { this.jobDao = jobDao; }

    public List<Map<String, Object>> list(String q) {
        LoginUser me = AuthContext.require();
        return jobDao.listByUser(me.id(), q).stream().map(JobService::toClient).toList();
    }

    public Map<String, Object> get(long id) {
        LoginUser me = AuthContext.require();
        return toClient(requireOwn(id, me));
    }

    public Map<String, Object> save(Map<String, Object> payload) {
        LoginUser me = AuthContext.require();
        String title = str(payload.get("title"));
        String company = str(payload.get("company"));
        String jd = str(payload.get("jd"));
        List<String> keywords = new ArrayList<>();
        if (payload.get("keywords") instanceof List<?> list) {
            for (Object o : list) {
                String k = str(o);
                if (!k.isEmpty()) keywords.add(k);
            }
        }

        if (title.isEmpty()) throw new BizException("岗位名称不能为空");
        if (title.length() > 50) throw new BizException("岗位名称不能超过 50 字");
        if (company.length() > 50) throw new BizException("公司名称不能超过 50 字");
        if (jd.length() < 10) throw new BizException("JD 文本不能少于 10 字");
        if (jd.length() > 5000) throw new BizException("JD 文本不能超过 5000 字");
        String keywordStr = String.join(",", keywords);
        if (keywordStr.length() > 500) throw new BizException("技能关键词总长度不能超过 500 字");

        Long id = payload.get("id") == null || String.valueOf(payload.get("id")).isBlank()
                ? null : Long.valueOf(String.valueOf(payload.get("id")));
        if (id == null) {
            long newId = jobDao.insert(me.id(), title, company, jd, keywordStr);
            if (newId < 0) throw new BizException("保存失败，请稍后重试");
            return toClient(jobDao.findById(newId).orElseThrow());
        }
        JobDao.JobRow existing = requireOwn(id, me);
        jobDao.update(existing.id(), title, company, jd, keywordStr);
        return toClient(jobDao.findById(existing.id()).orElseThrow());
    }

    public JobDao.JobRow requireOwn(long jobId, LoginUser me) {
        JobDao.JobRow j = jobDao.findById(jobId)
                .orElseThrow(() -> new BizException("岗位不存在"));
        if (j.userId() != me.id()) throw new BizException("无权访问该岗位");
        return j;
    }

    static Map<String, Object> toClient(JobDao.JobRow j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(j.id()));
        m.put("title", j.title());
        m.put("company", j.company() == null ? "" : j.company());
        m.put("jd", j.jd());
        m.put("keywords", j.keywords() == null || j.keywords().isBlank()
                ? List.of()
                : Arrays.stream(j.keywords().split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList());
        m.put("updatedAt", j.updatedAt());
        return m;
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o).trim(); }
}
