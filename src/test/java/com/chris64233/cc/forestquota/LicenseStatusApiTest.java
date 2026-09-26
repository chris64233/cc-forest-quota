package com.chris64233.cc.forestquota;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class LicenseStatusApiTest {

    @Autowired
    private MockMvc mockMvc;

    private String newSeasonWithQuota() throws Exception {
        MvcResult seasonResult = mockMvc.perform(post("/api/seasons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"forestArea": "红山林区", "startDate": "2026-01-01", "endDate": "2027-12-31"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String seasonId = com.jayway.jsonpath.JsonPath.read(
                seasonResult.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(post("/api/seasons/{seasonId}/quotas", seasonId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"species": "落叶松", "authorizedVolume": 100}
                                """))
                .andExpect(status().isCreated());
        return seasonId;
    }

    private void approve(String seasonId, String applicationNo) throws Exception {
        mockMvc.perform(post("/api/licenses/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationNo": "%s",
                                  "seasonId": %s,
                                  "workStartDate": "2026-03-01",
                                  "workEndDate": "2027-06-30",
                                  "items": [{"species": "落叶松", "volume": 40}]
                                }
                                """.formatted(applicationNo, seasonId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void suspendResumeRevokeOverHttp() throws Exception {
        String seasonId = newSeasonWithQuota();
        approve(seasonId, "API-SUS-001");

        mockMvc.perform(post("/api/licenses/{applicationNo}/suspension", "API-SUS-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventNo": "EVT-1", "reason": "暴雪封山", "effectiveAt": "2026-04-01T00:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        // 暂停期间不能新增实采申报
        mockMvc.perform(post("/api/licenses/{applicationNo}/settlement", "API-SUS-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items": [{"species": "落叶松", "volume": 10}]}
                                """))
                .andExpect(status().isConflict());

        mockMvc.perform(get("/api/licenses/{applicationNo}/resume-check", "API-SUS-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumable").value(true))
                .andExpect(jsonPath("$.blockers").isEmpty());

        mockMvc.perform(post("/api/licenses/{applicationNo}/resumption", "API-SUS-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventNo": "EVT-2", "effectiveAt": "2026-05-01T00:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(post("/api/licenses/{applicationNo}/revocation", "API-SUS-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventNo": "EVT-3", "reason": "吊销许可", "effectiveAt": "2026-06-01T00:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.items[0].releasedVolume").value(40.0));

        mockMvc.perform(get("/api/licenses/{applicationNo}/timeline", "API-SUS-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].eventType").value("SUSPENDED"))
                .andExpect(jsonPath("$[0].reason").value("暴雪封山"))
                .andExpect(jsonPath("$[1].eventType").value("RESUMED"))
                .andExpect(jsonPath("$[2].eventType").value("REVOKED"));

        // 撤销后占用全部释放
        mockMvc.perform(get("/api/seasons/{seasonId}/ledger", seasonId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotas[0].occupiedVolume").value(0.0))
                .andExpect(jsonPath("$.quotas[0].availableVolume").value(100.0));
    }

    @Test
    void statusEventIdempotencyAndValidationOverHttp() throws Exception {
        String seasonId = newSeasonWithQuota();
        approve(seasonId, "API-SUS-002");

        String suspendBody = """
                {"eventNo": "EVT-1", "reason": "林区封闭", "effectiveAt": "2026-04-01T00:00:00Z"}
                """;
        mockMvc.perform(post("/api/licenses/{applicationNo}/suspension", "API-SUS-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suspendBody))
                .andExpect(status().isOk());
        // 相同事件号相同内容幂等重放
        mockMvc.perform(post("/api/licenses/{applicationNo}/suspension", "API-SUS-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(suspendBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));
        // 相同事件号不同内容冲突
        mockMvc.perform(post("/api/licenses/{applicationNo}/suspension", "API-SUS-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventNo": "EVT-1", "reason": "其他原因", "effectiveAt": "2026-04-01T00:00:00Z"}
                                """))
                .andExpect(status().isConflict());
        // 暂停必须给出原因
        mockMvc.perform(post("/api/licenses/{applicationNo}/suspension", "API-SUS-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventNo": "EVT-9", "effectiveAt": "2026-04-02T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest());
        // 生效时间不能早于上一事件
        mockMvc.perform(post("/api/licenses/{applicationNo}/resumption", "API-SUS-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventNo": "EVT-2", "effectiveAt": "2026-03-01T00:00:00Z"}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/licenses/{applicationNo}/timeline", "API-SUS-404"))
                .andExpect(status().isNotFound());
    }
}
