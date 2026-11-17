/* ============================================================
   mock.js — 单一模拟数据源（首次打开时写入 localStorage）
   业务数据均为虚构演示数据，不对应真实个人。
   ============================================================ */

window.MOCK = {
  users: [
    { id: 'u1', account: 'linzhiyuan', password: '123456', name: '林知远', role: 'student', status: 'active', createdAt: '2026-09-02', analyses: 12 },
    { id: 'u2', account: 'suqing', password: '123456', name: '苏晴', role: 'student', status: 'active', createdAt: '2026-09-05', analyses: 8 },
    { id: 'u3', account: 'chenyu', password: '123456', name: '陈昱', role: 'student', status: 'active', createdAt: '2026-09-08', analyses: 5 },
    { id: 'u4', account: 'heyutong', password: '123456', name: '何雨桐', role: 'student', status: 'disabled', createdAt: '2026-09-11', analyses: 3 },
    { id: 'u5', account: 'zhaoyiming', password: '123456', name: '赵一鸣', role: 'student', status: 'active', createdAt: '2026-09-14', analyses: 9 },
    { id: 'u6', account: 'wangsiyuan', password: '123456', name: '王思远', role: 'student', status: 'active', createdAt: '2026-09-19', analyses: 2 },
    { id: 'a1', account: 'admin', password: 'admin123', name: '周明', role: 'admin', status: 'active', createdAt: '2026-08-20', analyses: 0 }
  ],

  resumes: [
    {
      id: 'r1', userId: 'u1', name: '后端开发实习 · 主简历', target: 'Java 后端开发实习生',
      phone: '138****6621', email: 'linzhiyuan@example.com',
      summary: '软件工程本科在读，具备 Spring Boot + Vue 全栈项目经验，熟悉 MySQL 与 Redis，正在寻找 Java 后端开发实习机会。',
      education: [{ school: '江南大学', major: '软件工程', degree: '本科', period: '2023.09 - 2027.06', gpa: '3.7 / 4.0' }],
      projects: [
        { title: '校园二手交易平台', role: '后端负责人', period: '2025.03 - 2025.09', desc: '使用 Spring Boot + MyBatis + MySQL 实现商品发布、订单与站内通知模块；引入 Redis 缓存商品列表与热门搜索，列表接口平均响应从 420ms 降至 130ms；通过 RabbitMQ 异步处理下单通知。' },
        { title: '实验室设备预约系统', role: '全栈开发', period: '2025.10 - 2026.01', desc: '负责预约时段冲突校验与审批流开发，使用 Vue 3 + Element Plus 完成管理端页面，支持 300 余名师生使用。' }
      ],
      skills: ['Java', 'Spring Boot', 'Spring MVC', 'MyBatis', 'MySQL', 'Redis', 'RabbitMQ', 'Vue 3', 'Git', 'Linux'],
      version: 3, updatedAt: '2026-10-22 14:05', completeness: 92
    },
    {
      id: 'r2', userId: 'u1', name: 'Java 后端 · 校招版', target: 'Java 后端开发工程师（校招）',
      phone: '138****6621', email: 'linzhiyuan@example.com',
      summary: '计算机相关专业本科，主攻 Java 后端方向，参与两个完整 Web 项目，关注接口性能与数据一致性。',
      education: [{ school: '江南大学', major: '软件工程', degree: '本科', period: '2023.09 - 2027.06', gpa: '3.7 / 4.0' }],
      projects: [
        { title: '校园二手交易平台', role: '后端开发', period: '2025.03 - 2025.09', desc: '负责商品与订单模块接口开发，使用 MyBatis 完成分页查询，Redis 缓存热点数据。' }
      ],
      skills: ['Java', 'Spring Boot', 'MyBatis', 'MySQL', 'Redis', 'Git'],
      version: 2, updatedAt: '2026-10-18 09:32', completeness: 84
    },
    {
      id: 'r3', userId: 'u1', name: '产品实习 · 尝试版', target: '产品经理实习生',
      phone: '138****6621', email: 'linzhiyuan@example.com',
      summary: '软件工程背景，参与校园项目需求整理与原型设计，希望尝试产品经理实习。',
      education: [{ school: '江南大学', major: '软件工程', degree: '本科', period: '2023.09 - 2027.06', gpa: '3.7 / 4.0' }],
      projects: [
        { title: '校园二手交易平台', role: '需求与原型', period: '2025.03 - 2025.09', desc: '完成用户访谈提纲与 20 页原型，输出需求文档并跟进开发验收。' }
      ],
      skills: ['Axure', 'Figma', '需求分析', 'SQL'],
      version: 1, updatedAt: '2026-10-10 16:48', completeness: 61
    },
    {
      id: 'r4', userId: 'u2', name: '前端开发实习简历', target: '前端开发实习生',
      phone: '139****2210', email: 'suqing@example.com',
      summary: '前端方向本科在读，熟悉 Vue 3 与工程化构建。',
      education: [{ school: '江南大学', major: '数字媒体技术', degree: '本科', period: '2023.09 - 2027.06', gpa: '3.6 / 4.0' }],
      projects: [{ title: '校园活动报名平台', role: '前端开发', period: '2025.04 - 2025.10', desc: '使用 Vue 3 + Vite 实现活动列表、报名表单与后台管理页面。' }],
      skills: ['Vue 3', 'JavaScript', 'TypeScript', 'Vite', 'CSS'],
      version: 2, updatedAt: '2026-10-20 11:10', completeness: 88
    },
    {
      id: 'r5', userId: 'u3', name: '测试开发实习简历', target: '测试开发实习生',
      phone: '137****8890', email: 'chenyu@example.com',
      summary: '熟悉接口测试与自动化测试框架。',
      education: [{ school: '江南大学', major: '计算机科学与技术', degree: '本科', period: '2023.09 - 2027.06', gpa: '3.5 / 4.0' }],
      projects: [{ title: '接口自动化测试平台', role: '开发', period: '2025.05 - 2025.11', desc: '基于 Pytest 编写 120 条接口用例，接入 Jenkins 持续集成。' }],
      skills: ['Python', 'Pytest', 'Postman', 'MySQL', 'Jenkins'],
      version: 1, updatedAt: '2026-10-19 15:26', completeness: 79
    },
    {
      id: 'r6', userId: 'u5', name: '后端开发简历', target: 'Java 后端开发实习生',
      phone: '136****4432', email: 'zhaoyiming@example.com',
      summary: 'Java 后端方向，关注并发与中间件。',
      education: [{ school: '江南大学', major: '软件工程', degree: '本科', period: '2023.09 - 2027.06', gpa: '3.8 / 4.0' }],
      projects: [{ title: '校园跑腿系统', role: '后端开发', period: '2025.02 - 2025.08', desc: '使用 Spring Boot + MySQL 实现订单撮合与状态流转，接入 Redis 分布式锁。' }],
      skills: ['Java', 'Spring Boot', 'MySQL', 'Redis', 'Kafka'],
      version: 2, updatedAt: '2026-10-21 17:03', completeness: 86
    },
    {
      id: 'r7', userId: 'u6', name: '数据分析实习简历', target: '数据分析实习生',
      phone: '135****1123', email: 'wangsiyuan@example.com',
      summary: '统计与数据分析方向。',
      education: [{ school: '江南大学', major: '统计学', degree: '本科', period: '2023.09 - 2027.06', gpa: '3.6 / 4.0' }],
      projects: [{ title: '校园消费数据分析', role: '分析', period: '2025.06 - 2025.10', desc: '使用 Python 清洗 3 万条消费记录，产出可视化分析报告。' }],
      skills: ['Python', 'SQL', 'Pandas', 'Tableau'],
      version: 1, updatedAt: '2026-10-18 10:20', completeness: 72
    },
    {
      id: 'r8', userId: 'u2', name: '产品运营简历', target: '产品运营实习生',
      phone: '139****2210', email: 'suqing@example.com',
      summary: '有校园活动运营经验。',
      education: [{ school: '江南大学', major: '数字媒体技术', degree: '本科', period: '2023.09 - 2027.06', gpa: '3.6 / 4.0' }],
      projects: [{ title: '校园活动报名平台', role: '运营与前端', period: '2025.04 - 2025.10', desc: '负责活动策划、用户增长与报名转化分析。' }],
      skills: ['活动运营', '数据分析', 'Figma'],
      version: 1, updatedAt: '2026-10-15 09:40', completeness: 68
    },
    {
      id: 'r9', userId: 'u4', name: 'Java 实习简历', target: 'Java 后端开发实习生',
      phone: '134****7788', email: 'heyutong@example.com',
      summary: 'Java 基础扎实，参与过课程项目。',
      education: [{ school: '江南大学', major: '物联网工程', degree: '本科', period: '2023.09 - 2027.06', gpa: '3.4 / 4.0' }],
      projects: [{ title: '图书管理系统', role: '后端开发', period: '2025.03 - 2025.06', desc: '使用 Spring Boot + MyBatis 实现图书借阅与归还流程。' }],
      skills: ['Java', 'Spring Boot', 'MySQL'],
      version: 1, updatedAt: '2026-09-28 14:12', completeness: 64
    }
  ],

  jobs: [
    {
      id: 'j1', userId: 'u1', title: 'Java 后端开发实习生', company: '字节跳动',
      jd: '参与电商业务后端服务开发；要求熟悉 Java 与 Spring Boot，掌握 MySQL 索引与事务，了解 Redis 缓存与消息队列；具备分布式与高并发场景经验者优先；每周到岗 4 天以上，实习期 3 个月起。',
      keywords: ['Java', 'Spring Boot', 'MySQL', 'Redis', '消息队列', '分布式', '高并发'], updatedAt: '2026-10-22 13:40'
    },
    {
      id: 'j2', userId: 'u1', title: '后端研发实习生', company: '阿里巴巴',
      jd: '负责交易链路服务研发；要求扎实的 Java 基础，熟悉 JVM 内存模型与并发编程，了解常用中间件；有性能调优经验优先。',
      keywords: ['Java', 'JVM', '并发编程', '中间件', 'MySQL', '性能调优'], updatedAt: '2026-10-21 09:55'
    },
    {
      id: 'j3', userId: 'u1', title: '后台开发实习生', company: '腾讯',
      jd: '负责后台服务开发与维护；熟悉 C++ 或 Go，掌握 Linux 下开发调试，理解网络协议与数据结构；有高并发服务经验优先。',
      keywords: ['C++', 'Go', 'Linux', '网络协议', '数据结构', '高并发'], updatedAt: '2026-10-20 18:22'
    },
    {
      id: 'j4', userId: 'u1', title: '后端开发实习生', company: '美团',
      jd: '参与本地生活服务后端开发；要求熟悉 Java 与 Spring 体系，掌握 MySQL 与 Redis，具备高并发场景下的接口设计能力。',
      keywords: ['Java', 'Spring', 'MySQL', 'Redis', '高并发', '接口设计'], updatedAt: '2026-10-19 11:08'
    },
    {
      id: 'j5', userId: 'u1', title: '服务端开发实习生', company: '小红书',
      jd: '负责社区服务端功能开发；熟悉 Java 或 Go，掌握 MySQL、Redis 与 Kafka；关注代码质量与工程规范。',
      keywords: ['Java', 'Go', 'MySQL', 'Redis', 'Kafka', '工程规范'], updatedAt: '2026-10-18 16:30'
    },
    {
      id: 'j6', userId: 'u2', title: '前端开发实习生', company: '网易',
      jd: '负责 Web 前端开发；熟悉 Vue 3 与工程化工具，了解 TypeScript 与组件化设计。',
      keywords: ['Vue 3', 'TypeScript', 'Vite', 'CSS', '组件化'], updatedAt: '2026-10-20 10:00'
    },
    {
      id: 'j7', userId: 'u3', title: '测试开发实习生', company: '拼多多',
      jd: '负责接口自动化测试与质量保障；熟悉 Python、Pytest 与持续集成。',
      keywords: ['Python', 'Pytest', '接口测试', 'Jenkins', 'MySQL'], updatedAt: '2026-10-19 14:20'
    },
    {
      id: 'j8', userId: 'u5', title: 'Java 后端开发实习生', company: '京东',
      jd: '参与订单系统开发；熟悉 Java、Spring Boot、MySQL 与 Redis。',
      keywords: ['Java', 'Spring Boot', 'MySQL', 'Redis', '订单'], updatedAt: '2026-10-21 15:10'
    },
    {
      id: 'j9', userId: 'u6', title: '数据分析实习生', company: '携程',
      jd: '负责业务数据分析；熟悉 SQL、Python 与可视化工具。',
      keywords: ['SQL', 'Python', 'Pandas', 'Tableau', '数据分析'], updatedAt: '2026-10-17 09:30'
    },
    {
      id: 'j10', userId: 'u4', title: 'Java 后端开发实习生', company: '百度',
      jd: '负责基础服务开发；熟悉 Java 与 Spring Boot，掌握 MySQL。',
      keywords: ['Java', 'Spring Boot', 'MySQL', 'Linux'], updatedAt: '2026-09-27 13:00'
    }
  ],

  analyses: [
    {
      id: 'a1', userId: 'u1', resumeId: 'r1', jobId: 'j1', resumeName: '后端开发实习 · 主简历', jobTitle: 'Java 后端开发实习生 · 字节跳动',
      status: 'success', mode: 'ai', score: 82, createdAt: '2026-10-22 14:20', duration: 12.4,
      missing: ['分布式事务', '性能压测', '高可用'],
      suggestions: [
        { module: '项目经历', level: 'high', text: '二手交易平台的项目描述偏重功能罗列，建议按「背景—职责—技术方案—结果」重写，并补充接口耗时、QPS 或数据量等可量化结果。' },
        { module: '技能清单', level: 'medium', text: 'JD 要求消息队列经验，简历中 RabbitMQ 仅在项目描述中一笔带过，建议在技能清单单列并注明使用场景。' },
        { module: '个人信息', level: 'low', text: '求职意向与岗位名称一致，建议补充可实习周期、每周到岗天数与期望城市，减少 HR 沟通成本。' }
      ],
      feedback: { type: 'useful', note: '项目描述的建议很具体', at: '2026-10-22 14:32' }
    },
    {
      id: 'a2', userId: 'u1', resumeId: 'r1', jobId: 'j2', resumeName: '后端开发实习 · 主简历', jobTitle: '后端研发实习生 · 阿里巴巴',
      status: 'success', mode: 'ai', score: 76, createdAt: '2026-10-21 10:05', duration: 11.2,
      missing: ['JVM 调优', '并发编程', '中间件'],
      suggestions: [
        { module: '技能清单', level: 'high', text: 'JD 明确要求 JVM 与并发编程，简历技能清单未体现，建议补充相关课程实践或项目中的线程池、锁优化经验。' },
        { module: '项目经历', level: 'medium', text: '建议补充一次接口性能问题的排查过程，说明使用的工具（Arthas、JProfiler 等）与最终结果。' },
        { module: '教育经历', level: 'low', text: '可补充与岗位相关的核心课程成绩，如操作系统、数据结构。' }
      ],
      feedback: { type: 'useful', note: '', at: '2026-10-21 10:20' }
    },
    {
      id: 'a3', userId: 'u1', resumeId: 'r2', jobId: 'j4', resumeName: 'Java 后端 · 校招版', jobTitle: '后端开发实习生 · 美团',
      status: 'success', mode: 'ai', score: 71, createdAt: '2026-10-20 16:40', duration: 13.8,
      missing: ['高并发', '接口设计'],
      suggestions: [
        { module: '项目经历', level: 'high', text: '校招版简历只有一个项目且描述较短，建议补充第二个项目中你独立负责的模块与产出。' },
        { module: '技能清单', level: 'medium', text: 'JD 强调高并发与接口设计，建议在项目中补充限流、缓存或分页设计的说明。' }
      ],
      feedback: null
    },
    {
      id: 'a4', userId: 'u1', resumeId: 'r1', jobId: 'j3', resumeName: '后端开发实习 · 主简历', jobTitle: '后台开发实习生 · 腾讯',
      status: 'failed', mode: 'ai', score: null, createdAt: '2026-10-19 09:12', duration: 30,
      reason: 'AI 服务响应超时（超过 30 秒），本次分析未生成结果。',
      missing: [], suggestions: [], feedback: null
    },
    {
      id: 'a5', userId: 'u1', resumeId: 'r3', jobId: 'j5', resumeName: '产品实习 · 尝试版', jobTitle: '服务端开发实习生 · 小红书',
      status: 'success', mode: 'rule', score: 58, createdAt: '2026-10-18 20:14', duration: 1.1,
      missing: ['Java', 'Go', 'Kafka', '工程规范'],
      suggestions: [
        { module: '技能清单', level: 'high', text: '规则模式仅做关键词比对：JD 中的 Java、Go、Kafka 未在简历中出现，请核实是否具备并在简历中体现。' },
        { module: '项目经历', level: 'medium', text: '当前简历更偏产品方向，若投递服务端岗位，建议补充后端开发项目与技术细节。' }
      ],
      feedback: { type: 'useless', note: '规则模式建议偏泛', at: '2026-10-18 20:22' }
    },
    {
      id: 'a6', userId: 'u1', resumeId: 'r2', jobId: 'j1', resumeName: 'Java 后端 · 校招版', jobTitle: 'Java 后端开发实习生 · 字节跳动',
      status: 'success', mode: 'ai', score: 74, createdAt: '2026-10-17 11:30', duration: 12.0,
      missing: ['消息队列', '分布式', '高并发'], suggestions: [], feedback: null
    },
    {
      id: 'a7', userId: 'u1', resumeId: 'r1', jobId: 'j4', resumeName: '后端开发实习 · 主简历', jobTitle: '后端开发实习生 · 美团',
      status: 'success', mode: 'ai', score: 79, createdAt: '2026-10-16 15:22', duration: 10.6,
      missing: ['接口设计'], suggestions: [], feedback: null
    },
    {
      id: 'a8', userId: 'u1', resumeId: 'r3', jobId: 'j2', resumeName: '产品实习 · 尝试版', jobTitle: '后端研发实习生 · 阿里巴巴',
      status: 'success', mode: 'rule', score: 61, createdAt: '2026-10-15 19:02', duration: 1.3,
      missing: ['Java', 'JVM', '并发编程', '中间件'], suggestions: [], feedback: null
    },
    {
      id: 'a9', userId: 'u1', resumeId: 'r2', jobId: 'j3', resumeName: 'Java 后端 · 校招版', jobTitle: '后台开发实习生 · 腾讯',
      status: 'failed', mode: 'ai', score: null, createdAt: '2026-10-14 21:45', duration: 9.8,
      reason: 'AI 返回内容格式不符合约定，本次结果已丢弃，可重试。',
      missing: [], suggestions: [], feedback: null
    },
    {
      id: 'a10', userId: 'u1', resumeId: 'r1', jobId: 'j5', resumeName: '后端开发实习 · 主简历', jobTitle: '服务端开发实习生 · 小红书',
      status: 'success', mode: 'ai', score: 84, createdAt: '2026-10-13 10:18', duration: 11.9,
      missing: ['Go', 'Kafka'], suggestions: [], feedback: { type: 'useful', note: '', at: '2026-10-13 10:31' }
    },
    {
      id: 'a11', userId: 'u1', resumeId: 'r2', jobId: 'j2', resumeName: 'Java 后端 · 校招版', jobTitle: '后端研发实习生 · 阿里巴巴',
      status: 'success', mode: 'ai', score: 77, createdAt: '2026-10-12 14:50', duration: 12.7,
      missing: ['JVM 调优', '中间件'], suggestions: [], feedback: null
    },
    {
      id: 'a12', userId: 'u1', resumeId: 'r1', jobId: 'j1', resumeName: '后端开发实习 · 主简历', jobTitle: 'Java 后端开发实习生 · 字节跳动',
      status: 'success', mode: 'ai', score: 68, createdAt: '2026-10-10 09:30', duration: 13.1,
      missing: ['消息队列', '分布式', '性能压测', '高可用'], suggestions: [], feedback: null
    },
    { id: 'a13', userId: 'u2', resumeId: 'r4', jobId: 'j6', resumeName: '前端开发实习简历', jobTitle: '前端开发实习生 · 网易', status: 'success', mode: 'ai', score: 81, createdAt: '2026-10-22 11:02', duration: 11.4, missing: ['组件化'], suggestions: [], feedback: null },
    { id: 'a14', userId: 'u3', resumeId: 'r5', jobId: 'j7', resumeName: '测试开发实习简历', jobTitle: '测试开发实习生 · 拼多多', status: 'success', mode: 'ai', score: 73, createdAt: '2026-10-22 09:46', duration: 10.8, missing: ['持续集成'], suggestions: [], feedback: null },
    { id: 'a15', userId: 'u5', resumeId: 'r6', jobId: 'j8', resumeName: '后端开发简历', jobTitle: 'Java 后端开发实习生 · 京东', status: 'success', mode: 'ai', score: 69, createdAt: '2026-10-21 20:15', duration: 12.2, missing: ['订单', '高并发'], suggestions: [], feedback: null },
    { id: 'a16', userId: 'u2', resumeId: 'r8', jobId: 'j7', resumeName: '产品运营简历', jobTitle: '测试开发实习生 · 拼多多', status: 'failed', mode: 'ai', score: null, createdAt: '2026-10-21 16:40', duration: 30, reason: 'AI 服务响应超时（超过 30 秒），本次分析未生成结果。', missing: [], suggestions: [], feedback: null },
    { id: 'a17', userId: 'u6', resumeId: 'r7', jobId: 'j9', resumeName: '数据分析实习简历', jobTitle: '数据分析实习生 · 携程', status: 'success', mode: 'rule', score: 64, createdAt: '2026-10-20 14:10', duration: 1.2, missing: ['SQL', 'Tableau'], suggestions: [], feedback: null },
    { id: 'a18', userId: 'u5', resumeId: 'r6', jobId: 'j4', resumeName: '后端开发简历', jobTitle: '后端开发实习生 · 美团', status: 'success', mode: 'ai', score: 80, createdAt: '2026-10-19 19:32', duration: 11.6, missing: ['接口设计'], suggestions: [], feedback: null },
    { id: 'a19', userId: 'u3', resumeId: 'r5', jobId: 'j1', resumeName: '测试开发实习简历', jobTitle: 'Java 后端开发实习生 · 字节跳动', status: 'success', mode: 'ai', score: 55, createdAt: '2026-10-18 13:24', duration: 12.9, missing: ['Java', 'Spring Boot', 'Redis'], suggestions: [], feedback: null },
    { id: 'a20', userId: 'u4', resumeId: 'r9', jobId: 'j10', resumeName: 'Java 实习简历', jobTitle: 'Java 后端开发实习生 · 百度', status: 'success', mode: 'ai', score: 66, createdAt: '2026-10-16 10:08', duration: 11.1, missing: ['Linux'], suggestions: [], feedback: null }
  ],

  versions: [
    {
      id: 'v1', resumeId: 'r1', version: 1, createdAt: '2026-09-28 20:10', sourceTaskId: null, note: '初稿：整理校园项目与技能清单',
      snapshot: {
        summary: '软件工程本科在读，熟悉 Java Web 开发，寻求后端开发实习机会。',
        skills: ['Java', 'Spring Boot', 'MySQL', 'Git'],
        projects: [{ title: '校园二手交易平台', desc: '负责商品发布与订单模块，使用 Spring Boot + MySQL 实现基础增删改查。' }]
      }
    },
    {
      id: 'v2', resumeId: 'r1', version: 2, createdAt: '2026-10-12 21:36', sourceTaskId: 'a12', note: '补充 Redis 缓存与接口性能数据',
      snapshot: {
        summary: '软件工程本科在读，具备 Spring Boot + Vue 全栈项目经验，熟悉 MySQL 与 Redis。',
        skills: ['Java', 'Spring Boot', 'Spring MVC', 'MyBatis', 'MySQL', 'Redis', 'Vue 3', 'Git'],
        projects: [{ title: '校园二手交易平台', desc: '使用 Spring Boot + MyBatis + MySQL 实现商品、订单模块；引入 Redis 缓存商品列表与热门搜索，列表接口平均响应从 420ms 降至 130ms。' }]
      }
    },
    {
      id: 'v3', resumeId: 'r1', version: 3, createdAt: '2026-10-22 14:26', sourceTaskId: 'a1', note: '按字节跳动 JD 补充 RabbitMQ 与压测说明',
      snapshot: {
        summary: '软件工程本科在读，具备 Spring Boot + Vue 全栈项目经验，熟悉 MySQL、Redis 与 RabbitMQ，正在寻找 Java 后端开发实习机会。',
        skills: ['Java', 'Spring Boot', 'Spring MVC', 'MyBatis', 'MySQL', 'Redis', 'RabbitMQ', 'Vue 3', 'Git', 'Linux'],
        projects: [{ title: '校园二手交易平台', desc: '使用 Spring Boot + MyBatis + MySQL 实现商品发布、订单与站内通知模块；引入 Redis 缓存，列表接口平均响应从 420ms 降至 130ms；通过 RabbitMQ 异步处理下单通知。' }]
      }
    },
    {
      id: 'v4', resumeId: 'r2', version: 1, createdAt: '2026-10-02 15:20', sourceTaskId: null, note: '校招版初稿',
      snapshot: {
        summary: '计算机相关专业本科，主攻 Java 后端方向。',
        skills: ['Java', 'Spring Boot', 'MyBatis', 'MySQL', 'Git'],
        projects: [{ title: '校园二手交易平台', desc: '负责商品与订单模块接口开发。' }]
      }
    },
    {
      id: 'v5', resumeId: 'r2', version: 2, createdAt: '2026-10-18 09:32', sourceTaskId: 'a6', note: '补充 Redis 缓存与项目职责',
      snapshot: {
        summary: '计算机相关专业本科，主攻 Java 后端方向，参与两个完整 Web 项目，关注接口性能与数据一致性。',
        skills: ['Java', 'Spring Boot', 'MyBatis', 'MySQL', 'Redis', 'Git'],
        projects: [{ title: '校园二手交易平台', desc: '负责商品与订单模块接口开发，使用 MyBatis 完成分页查询，Redis 缓存热点数据。' }]
      }
    },
    {
      id: 'v6', resumeId: 'r3', version: 1, createdAt: '2026-10-10 16:48', sourceTaskId: null, note: '产品方向初次尝试',
      snapshot: {
        summary: '软件工程背景，参与校园项目需求整理与原型设计，希望尝试产品经理实习。',
        skills: ['Axure', 'Figma', '需求分析', 'SQL'],
        projects: [{ title: '校园二手交易平台', desc: '完成用户访谈提纲与 20 页原型，输出需求文档并跟进开发验收。' }]
      }
    }
  ]
};
