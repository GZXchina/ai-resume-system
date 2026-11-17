-- ============================================================
-- AI 简历优化系统 · 演示种子数据（MySQL 5.5.15）
-- 执行：mysql -u root -p ai_resume < data.sql
-- 所有数据均为虚构演示数据
-- ============================================================

USE ai_resume;

INSERT INTO sys_user (id, account, password_hash, name, role, status, created_at) VALUES
(1, 'linzhiyuan', '120000:bGluLXNhbHQtMjAyNg==:REjDEkz7PLsFZRj1UQxW65CEpXj97vUILbtQTBcoYfE=', '林知远', 'STUDENT', 'ACTIVE', '2026-09-02 09:00:00'),
(2, 'suqing',     '120000:c3Utc2FsdC0yMDI2:Gr6E4nx1Jc3R8V4YcwGHLKAKDpVvDhKMyrAj9+RkCQw=', '苏晴', 'STUDENT', 'ACTIVE', '2026-09-05 09:00:00'),
(3, 'chenyu',     '120000:Y2hlbi1zYWx0LTIwMjY=:sDFV2A1SFk0E+ShMN3PBcnbKoRHj8Dkl8nvvkvP5K5I=', '陈昱', 'STUDENT', 'ACTIVE', '2026-09-08 09:00:00'),
(4, 'heyutong',   '120000:aGUtc2FsdC0yMDI2:5kNEW73TxsCow+tkYc1ay0IbEDt9eK4hvaT+84doCoc=', '何雨桐', 'STUDENT', 'DISABLED', '2026-09-11 09:00:00'),
(5, 'zhaoyiming', '120000:emhhby1zYWx0LTIwMjY=:jqI+VgVheNKXeug3keoGLLDNIDVsCHKhmtYVhaenNxQ=', '赵一鸣', 'STUDENT', 'ACTIVE', '2026-09-14 09:00:00'),
(6, 'wangsiyuan', '120000:d2FuZy1zYWx0LTIwMjY=:qQ3KP+4v/F/qrvRDoy+PJhLMPVYSOw7kuAgRnkiyubg=', '王思远', 'STUDENT', 'ACTIVE', '2026-09-19 09:00:00'),
(7, 'admin',      '120000:YWRtaW4tc2FsdC0yMDI2:17AZeVAQ7/c+xH2H+lTfkLnP7vvp3zeQYpFnjhNOQRI=', '周明', 'ADMIN', 'ACTIVE', '2026-08-20 09:00:00');

INSERT INTO resume (id, user_id, name, person_name, target, phone, email, summary, version, completeness, created_at, updated_at) VALUES
(1, 1, '后端开发实习 · 主简历', '林知远', 'Java 后端开发实习生', '138****6621', 'linzhiyuan@example.com', '软件工程本科在读，具备 Spring Boot + Vue 全栈项目经验，熟悉 MySQL 与 Redis，正在寻找 Java 后端开发实习机会。', 3, 92, '2026-09-20 10:00:00', '2026-10-22 14:05:00'),
(2, 1, 'Java 后端 · 校招版', '林知远', 'Java 后端开发工程师（校招）', '138****6621', 'linzhiyuan@example.com', '计算机相关专业本科，主攻 Java 后端方向，参与两个完整 Web 项目，关注接口性能与数据一致性。', 2, 84, '2026-09-25 10:00:00', '2026-10-18 09:32:00'),
(3, 1, '产品实习 · 尝试版', '林知远', '产品经理实习生', '138****6621', 'linzhiyuan@example.com', '软件工程背景，参与校园项目需求整理与原型设计，希望尝试产品经理实习。', 1, 61, '2026-10-10 16:48:00', '2026-10-10 16:48:00'),
(4, 2, '前端开发实习简历', '苏晴', '前端开发实习生', '139****2210', 'suqing@example.com', '前端方向本科在读，熟悉 Vue 3 与工程化构建。', 2, 88, '2026-09-22 10:00:00', '2026-10-20 11:10:00'),
(5, 3, '测试开发实习简历', '陈昱', '测试开发实习生', '137****8890', 'chenyu@example.com', '熟悉接口测试与自动化测试框架。', 1, 79, '2026-09-26 10:00:00', '2026-10-19 15:26:00'),
(6, 5, '后端开发简历', '赵一鸣', 'Java 后端开发实习生', '136****4432', 'zhaoyiming@example.com', 'Java 后端方向，关注并发与中间件。', 2, 86, '2026-09-28 10:00:00', '2026-10-21 17:03:00');

INSERT INTO resume_education (resume_id, school, major, degree, period, gpa, sort_no) VALUES
(1, '江南大学', '软件工程', '本科', '2023.09 - 2027.06', '3.7 / 4.0', 0),
(2, '江南大学', '软件工程', '本科', '2023.09 - 2027.06', '3.7 / 4.0', 0),
(3, '江南大学', '软件工程', '本科', '2023.09 - 2027.06', '3.7 / 4.0', 0),
(4, '江南大学', '数字媒体技术', '本科', '2023.09 - 2027.06', '3.6 / 4.0', 0),
(5, '江南大学', '计算机科学与技术', '本科', '2023.09 - 2027.06', '3.5 / 4.0', 0),
(6, '江南大学', '软件工程', '本科', '2023.09 - 2027.06', '3.8 / 4.0', 0);

INSERT INTO resume_project (resume_id, title, role, period, description, sort_no) VALUES
(1, '校园二手交易平台', '后端负责人', '2025.03 - 2025.09', '使用 Spring Boot + MyBatis + MySQL 实现商品发布、订单与站内通知模块；引入 Redis 缓存商品列表与热门搜索，列表接口平均响应从 420ms 降至 130ms；通过 RabbitMQ 异步处理下单通知。', 0),
(1, '实验室设备预约系统', '全栈开发', '2025.10 - 2026.01', '负责预约时段冲突校验与审批流开发，使用 Vue 3 + Element Plus 完成管理端页面，支持 300 余名师生使用。', 1),
(2, '校园二手交易平台', '后端开发', '2025.03 - 2025.09', '负责商品与订单模块接口开发，使用 MyBatis 完成分页查询，Redis 缓存热点数据。', 0),
(3, '校园二手交易平台', '需求与原型', '2025.03 - 2025.09', '完成用户访谈提纲与 20 页原型，输出需求文档并跟进开发验收。', 0),
(4, '校园活动报名平台', '前端开发', '2025.04 - 2025.10', '使用 Vue 3 + Vite 实现活动列表、报名表单与后台管理页面。', 0),
(5, '接口自动化测试平台', '开发', '2025.05 - 2025.11', '基于 Pytest 编写 120 条接口用例，接入 Jenkins 持续集成。', 0),
(6, '校园跑腿系统', '后端开发', '2025.02 - 2025.08', '使用 Spring Boot + MySQL 实现订单撮合与状态流转，接入 Redis 分布式锁。', 0);

INSERT INTO resume_skill (resume_id, skill, sort_no) VALUES
(1, 'Java', 0), (1, 'Spring Boot', 1), (1, 'Spring MVC', 2), (1, 'MyBatis', 3), (1, 'MySQL', 4), (1, 'Redis', 5), (1, 'RabbitMQ', 6), (1, 'Vue 3', 7), (1, 'Git', 8), (1, 'Linux', 9),
(2, 'Java', 0), (2, 'Spring Boot', 1), (2, 'MyBatis', 2), (2, 'MySQL', 3), (2, 'Redis', 4), (2, 'Git', 5),
(3, 'Axure', 0), (3, 'Figma', 1), (3, '需求分析', 2), (3, 'SQL', 3),
(4, 'Vue 3', 0), (4, 'JavaScript', 1), (4, 'TypeScript', 2), (4, 'Vite', 3), (4, 'CSS', 4),
(5, 'Python', 0), (5, 'Pytest', 1), (5, 'Postman', 2), (5, 'MySQL', 3), (5, 'Jenkins', 4),
(6, 'Java', 0), (6, 'Spring Boot', 1), (6, 'MySQL', 2), (6, 'Redis', 3), (6, 'Kafka', 4);

INSERT INTO job (id, user_id, title, company, jd, keywords, created_at, updated_at) VALUES
(1, 1, 'Java 后端开发实习生', '字节跳动', '参与电商业务后端服务开发；要求熟悉 Java 与 Spring Boot，掌握 MySQL 索引与事务，了解 Redis 缓存与消息队列；具备分布式与高并发场景经验者优先；每周到岗 4 天以上，实习期 3 个月起。', 'Java,Spring Boot,MySQL,Redis,消息队列,分布式,高并发', '2026-10-10 10:00:00', '2026-10-22 13:40:00'),
(2, 1, '后端研发实习生', '阿里巴巴', '负责交易链路服务研发；要求扎实的 Java 基础，熟悉 JVM 内存模型与并发编程，了解常用中间件；有性能调优经验优先。', 'Java,JVM,并发编程,中间件,MySQL,性能调优', '2026-10-11 10:00:00', '2026-10-21 09:55:00'),
(3, 1, '后台开发实习生', '腾讯', '负责后台服务开发与维护；熟悉 C++ 或 Go，掌握 Linux 下开发调试，理解网络协议与数据结构；有高并发服务经验优先。', 'C++,Go,Linux,网络协议,数据结构,高并发', '2026-10-12 10:00:00', '2026-10-20 18:22:00'),
(4, 1, '后端开发实习生', '美团', '参与本地生活服务后端开发；要求熟悉 Java 与 Spring 体系，掌握 MySQL 与 Redis，具备高并发场景下的接口设计能力。', 'Java,Spring,MySQL,Redis,高并发,接口设计', '2026-10-13 10:00:00', '2026-10-19 11:08:00'),
(5, 1, '服务端开发实习生', '小红书', '负责社区服务端功能开发；熟悉 Java 或 Go，掌握 MySQL、Redis 与 Kafka；关注代码质量与工程规范。', 'Java,Go,MySQL,Redis,Kafka,工程规范', '2026-10-14 10:00:00', '2026-10-18 16:30:00'),
(6, 2, '前端开发实习生', '网易', '负责 Web 前端开发；熟悉 Vue 3 与工程化工具，了解 TypeScript 与组件化设计。', 'Vue 3,TypeScript,Vite,CSS,组件化', '2026-10-15 10:00:00', '2026-10-20 10:00:00'),
(7, 3, '测试开发实习生', '拼多多', '负责接口自动化测试与质量保障；熟悉 Python、Pytest 与持续集成。', 'Python,Pytest,接口测试,Jenkins,MySQL', '2026-10-15 11:00:00', '2026-10-19 14:20:00'),
(8, 5, 'Java 后端开发实习生', '京东', '参与订单系统开发；熟悉 Java、Spring Boot、MySQL 与 Redis。', 'Java,Spring Boot,MySQL,Redis,订单', '2026-10-16 10:00:00', '2026-10-21 15:10:00');

INSERT INTO resume_version (id, resume_id, version, source_task_id, note, snapshot_json, created_at) VALUES
('v1', 1, 1, NULL, '初稿：整理校园项目与技能清单', '{"summary":"软件工程本科在读，熟悉 Java Web 开发，寻求后端开发实习机会。","skills":["Java","Spring Boot","MySQL","Git"],"projects":[{"title":"校园二手交易平台","desc":"负责商品发布与订单模块，使用 Spring Boot + MySQL 实现基础增删改查。"}]}', '2026-09-28 20:10:00'),
('v2', 1, 2, 'a10', '补充 Redis 缓存与接口性能数据', '{"summary":"软件工程本科在读，具备 Spring Boot + Vue 全栈项目经验，熟悉 MySQL 与 Redis。","skills":["Java","Spring Boot","Spring MVC","MyBatis","MySQL","Redis","Vue 3","Git"],"projects":[{"title":"校园二手交易平台","desc":"使用 Spring Boot + MyBatis + MySQL 实现商品、订单模块；引入 Redis 缓存商品列表与热门搜索，列表接口平均响应从 420ms 降至 130ms。"}]}', '2026-10-12 21:36:00'),
('v3', 1, 3, 'a1', '按字节跳动 JD 补充 RabbitMQ 与压测说明', '{"summary":"软件工程本科在读，具备 Spring Boot + Vue 全栈项目经验，熟悉 MySQL、Redis 与 RabbitMQ，正在寻找 Java 后端开发实习机会。","skills":["Java","Spring Boot","Spring MVC","MyBatis","MySQL","Redis","RabbitMQ","Vue 3","Git","Linux"],"projects":[{"title":"校园二手交易平台","desc":"使用 Spring Boot + MyBatis + MySQL 实现商品发布、订单与站内通知模块；引入 Redis 缓存，列表接口平均响应从 420ms 降至 130ms；通过 RabbitMQ 异步处理下单通知。"}]}', '2026-10-22 14:26:00'),
('v4', 2, 1, NULL, '校招版初稿', '{"summary":"计算机相关专业本科，主攻 Java 后端方向。","skills":["Java","Spring Boot","MyBatis","MySQL","Git"],"projects":[{"title":"校园二手交易平台","desc":"负责商品与订单模块接口开发。"}]}', '2026-10-02 15:20:00'),
('v5', 2, 2, 'a6', '补充 Redis 缓存与项目职责', '{"summary":"计算机相关专业本科，主攻 Java 后端方向，参与两个完整 Web 项目，关注接口性能与数据一致性。","skills":["Java","Spring Boot","MyBatis","MySQL","Redis","Git"],"projects":[{"title":"校园二手交易平台","desc":"负责商品与订单模块接口开发，使用 MyBatis 完成分页查询，Redis 缓存热点数据。"}]}', '2026-10-18 09:32:00'),
('v6', 3, 1, NULL, '产品方向初次尝试', '{"summary":"软件工程背景，参与校园项目需求整理与原型设计，希望尝试产品经理实习。","skills":["Axure","Figma","需求分析","SQL"],"projects":[{"title":"校园二手交易平台","desc":"完成用户访谈提纲与 20 页原型，输出需求文档并跟进开发验收。"}]}', '2026-10-10 16:48:00'),
('v7', 4, 1, NULL, '前端方向初稿', '{"summary":"前端方向本科在读，熟悉 Vue 3 与基础工程化。","skills":["Vue 3","JavaScript","CSS"],"projects":[{"title":"校园活动报名平台","desc":"使用 Vue 3 + Vite 实现活动列表与报名表单。"}]}', '2026-10-05 19:20:00'),
('v8', 4, 2, NULL, '补充 TypeScript 与后台管理页面', '{"summary":"前端方向本科在读，熟悉 Vue 3 与工程化构建。","skills":["Vue 3","JavaScript","TypeScript","Vite","CSS"],"projects":[{"title":"校园活动报名平台","desc":"使用 Vue 3 + Vite 实现活动列表、报名表单与后台管理页面。"}]}', '2026-10-20 11:10:00'),
('v9', 5, 1, NULL, '测试开发方向初稿', '{"summary":"熟悉接口测试与自动化测试框架。","skills":["Python","Pytest","Postman","MySQL","Jenkins"],"projects":[{"title":"接口自动化测试平台","desc":"基于 Pytest 编写 120 条接口用例，接入 Jenkins 持续集成。"}]}', '2026-09-26 10:00:00'),
('v10', 6, 1, NULL, '后端方向初稿', '{"summary":"Java 后端方向，具备 Spring Boot 项目经验。","skills":["Java","Spring Boot","MySQL","Redis"],"projects":[{"title":"校园跑腿系统","desc":"使用 Spring Boot + MySQL 实现订单撮合与状态流转。"}]}', '2026-10-01 14:30:00'),
('v11', 6, 2, NULL, '补充 Redis 分布式锁', '{"summary":"Java 后端方向，关注并发与中间件。","skills":["Java","Spring Boot","MySQL","Redis","Kafka"],"projects":[{"title":"校园跑腿系统","desc":"使用 Spring Boot + MySQL 实现订单撮合与状态流转，接入 Redis 分布式锁。"}]}', '2026-10-21 17:03:00');

INSERT INTO analysis_task (id, user_id, resume_id, job_id, resume_name, job_title, status, mode, score, duration, missing_keywords, suggestions_json, reason, feedback_type, feedback_note, feedback_at, demo_fail, created_at, updated_at) VALUES
('a1', 1, 1, 1, '后端开发实习 · 主简历', 'Java 后端开发实习生 · 字节跳动', 'SUCCESS', 'AI', 82, 12.4, '分布式事务,性能压测,高可用', '[{"module":"项目经历","level":"high","text":"二手交易平台的项目描述偏重功能罗列，建议按「背景—职责—技术方案—结果」重写，并补充接口耗时、QPS 或数据量等可量化结果。"},{"module":"技能清单","level":"medium","text":"JD 要求消息队列经验，简历中 RabbitMQ 仅在项目描述中一笔带过，建议在技能清单单列并注明使用场景。"},{"module":"个人信息","level":"low","text":"求职意向与岗位名称一致，建议补充可实习周期、每周到岗天数与期望城市。"}]', NULL, 'USEFUL', '项目描述的建议很具体', '2026-10-22 14:32:00', 0, '2026-10-22 14:20:00', '2026-10-22 14:32:00'),
('a2', 1, 1, 2, '后端开发实习 · 主简历', '后端研发实习生 · 阿里巴巴', 'SUCCESS', 'AI', 76, 11.2, 'JVM,并发编程,中间件', '[{"module":"技能清单","level":"high","text":"JD 明确要求 JVM 与并发编程，简历技能清单未体现，建议补充相关课程实践或项目中的线程池、锁优化经验。"},{"module":"项目经历","level":"medium","text":"建议补充一次接口性能问题的排查过程，说明使用的工具与最终结果。"}]', NULL, NULL, NULL, NULL, 0, '2026-10-21 10:05:00', '2026-10-21 10:05:00'),
('a3', 1, 2, 4, 'Java 后端 · 校招版', '后端开发实习生 · 美团', 'SUCCESS', 'AI', 71, 13.8, '高并发,接口设计', '[{"module":"项目经历","level":"high","text":"校招版简历只有一个项目且描述较短，建议补充第二个项目中你独立负责的模块与产出。"}]', NULL, NULL, NULL, NULL, 0, '2026-10-20 16:40:00', '2026-10-20 16:40:00'),
('a4', 1, 1, 3, '后端开发实习 · 主简历', '后台开发实习生 · 腾讯', 'FAILED', 'AI', NULL, 30.0, NULL, NULL, 'AI 服务响应超时（超过 30 秒），本次分析未生成结果。', NULL, NULL, NULL, 0, '2026-10-19 09:12:00', '2026-10-19 09:12:00'),
('a5', 1, 3, 5, '产品实习 · 尝试版', '服务端开发实习生 · 小红书', 'SUCCESS', 'RULE', 58, 1.1, 'Java,Go,Kafka,工程规范', '[{"module":"技能清单","level":"high","text":"规则模式仅做关键词比对：JD 中的 Java、Go、Kafka 未在简历中出现，请核实是否具备并在简历中体现。"},{"module":"项目经历","level":"medium","text":"当前简历更偏产品方向，若投递服务端岗位，建议补充后端开发项目与技术细节。"}]', NULL, 'USELESS', '规则模式建议偏泛', '2026-10-18 20:22:00', 0, '2026-10-18 20:14:00', '2026-10-18 20:22:00'),
('a6', 1, 2, 1, 'Java 后端 · 校招版', 'Java 后端开发实习生 · 字节跳动', 'SUCCESS', 'AI', 74, 12.0, '消息队列,分布式,高并发', '[{"module":"技能清单","level":"high","text":"建议补充消息队列与分布式相关实践，并在项目中说明使用场景。"}]', NULL, NULL, NULL, NULL, 0, '2026-10-17 11:30:00', '2026-10-17 11:30:00'),
('a7', 2, 4, 6, '前端开发实习简历', '前端开发实习生 · 网易', 'SUCCESS', 'AI', 81, 11.4, '组件化', '[{"module":"项目经历","level":"medium","text":"建议补充组件拆分与复用方式，体现组件化设计能力。"}]', NULL, NULL, NULL, NULL, 0, '2026-10-22 11:02:00', '2026-10-22 11:02:00'),
('a8', 5, 6, 8, '后端开发简历', 'Java 后端开发实习生 · 京东', 'SUCCESS', 'AI', 69, 12.2, '订单,高并发', '[{"module":"项目经历","level":"high","text":"建议补充订单状态流转的异常处理与幂等设计。"}]', NULL, NULL, NULL, NULL, 0, '2026-10-21 20:15:00', '2026-10-21 20:15:00'),
('a9', 3, 5, 7, '测试开发实习简历', '测试开发实习生 · 拼多多', 'FAILED', 'AI', NULL, 9.8, NULL, NULL, 'AI 返回内容格式不符合约定，本次结果已丢弃，可重试。', NULL, NULL, NULL, 0, '2026-10-14 21:45:00', '2026-10-14 21:45:00');
