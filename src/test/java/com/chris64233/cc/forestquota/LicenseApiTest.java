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
class LicenseApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void fullLifecycleOverHttp() throws Exception {
        MvcResult seasonResult = mockMvc.perform(post("/api/seasons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"forestArea": "红山林区", "startDate": "2026-01-01", "endDate": "2026-12-31"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();
        String seasonId = com.jayway.jsonpath.JsonPath.read(
                seasonResult.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(post("/api/seasons/{seasonId}/quotas", seasonId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"species": "落叶松", "authorizedVolume": 100}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.availableVolume").value(100.0));

        mockMvc.perform(post("/api/seasons/{seasonId}/quotas", seasonId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"species": "落叶松", "authorizedVolume": 50}
                                """))
                .andExpect(status().isConflict());

        String approveBody = """
                {
                  "applicationNo": "API-001",
                  "seasonId": %s,
                  "workStartDate": "2026-03-01",
                  "workEndDate": "2026-05-31",
                  "items": [{"species": "落叶松", "volume": 40}]
                }
                """.formatted(seasonId);

        mockMvc.perform(post("/api/licenses/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.items[0].approvedVolume").value(40.0));

        mockMvc.perform(post("/api/licenses/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approveBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        mockMvc.perform(get("/api/seasons/{seasonId}/ledger", seasonId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotas[0].occupiedVolume").value(40.0))
                .andExpect(jsonPath("$.quotas[0].availableVolume").value(60.0));

        mockMvc.perform(post("/api/licenses/{applicationNo}/settlement", "API-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items": [{"species": "落叶松", "volume": 25}]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.items[0].actualVolume").value(25.0))
                .andExpect(jsonPath("$.items[0].releasedVolume").value(15.0));

        mockMvc.perform(get("/api/licenses/{applicationNo}", "API-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));

        mockMvc.perform(get("/api/seasons/{seasonId}/ledger", seasonId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotas[0].occupiedVolume").value(0.0))
                .andExpect(jsonPath("$.quotas[0].harvestedVolume").value(25.0))
                .andExpect(jsonPath("$.quotas[0].availableVolume").value(75.0));

        mockMvc.perform(get("/api/licenses/{applicationNo}/audit-events", "API-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3));
    }

    @Test
    void suspensionRevocationFlowOverHttp() throws Exception {
        MvcResult seasonResult = mockMvc.perform(post("/api/seasons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"forestArea": "红山林区", "startDate": "2026-01-01", "endDate": "2099-12-31"}
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

        mockMvc.perform(post("/api/licenses/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationNo": "API-SUS-001",
                                  "seasonId": %s,
                                  "workStartDate": "2026-03-01",
                                  "workEndDate": "2099-12-31",
                                  "items": [{"species": "落叶松", "volume": 40}]
                                }
                                """.formatted(seasonId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 暂停：记录事件号、原因与生效时间
        mockMvc.perform(post("/api/licenses/{applicationNo}/suspension", "API-SUS-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventNo": 1, "reason": "暴雨红色预警", "effectiveAt": "2026-09-27T08:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        // 暂停期间不能申报实采
        mockMvc.perform(post("/api/licenses/{applicationNo}/settlement", "API-SUS-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items": [{"species": "落叶松", "volume": 25}]}
                                """))
                .andExpect(status().isConflict());

        // 相同事件号重复暂停幂等
        mockMvc.perform(post("/api/licenses/{applicationNo}/suspension", "API-SUS-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventNo": 1, "reason": "暴雨红色预警", "effectiveAt": "2026-09-27T08:00:00Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        // 事件号必须递增
        mockMvc.perform(post("/api/licenses/{applicationNo}/resumption", "API-SUS-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventNo": 1, "reason": "预警解除"}
                                """))
                .andExpect(status().isConflict());

        // 恢复前检查通过
        mockMvc.perform(get("/api/licenses/{applicationNo}/resume-blockers", "API-SUS-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resumable").value(true))
                .andExpect(jsonPath("$.reasons.length()").value(0));

        mockMvc.perform(post("/api/licenses/{applicationNo}/resumption", "API-SUS-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventNo": 2, "reason": "预警解除"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        // 撤销：一次性释放全部未实采占用
        mockMvc.perform(post("/api/licenses/{applicationNo}/revocation", "API-SUS-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"eventNo": 3, "reason": "严重违规"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.items[0].releasedVolume").value(40.0));

        // 撤销是终态
        mockMvc.perform(post("/api/licenses/{applicationNo}/settlement", "API-SUS-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items": [{"species": "落叶松", "volume": 25}]}
                                """))
                .andExpect(status().isConflict());

        // 额度全部释放回可用
        mockMvc.perform(get("/api/seasons/{seasonId}/ledger", seasonId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotas[0].occupiedVolume").value(0.0))
                .andExpect(jsonPath("$.quotas[0].harvestedVolume").value(0.0))
                .andExpect(jsonPath("$.quotas[0].availableVolume").value(100.0));

        // 状态时间线保持事件号顺序
        mockMvc.perform(get("/api/licenses/{applicationNo}/timeline", "API-SUS-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].eventNo").value(1))
                .andExpect(jsonPath("$[0].eventType").value("SUSPENDED"))
                .andExpect(jsonPath("$[0].reason").value("暴雨红色预警"))
                .andExpect(jsonPath("$[1].eventType").value("RESUMED"))
                .andExpect(jsonPath("$[2].eventType").value("REVOKED"));

        // 各树种占用与释放明细
        mockMvc.perform(get("/api/licenses/{applicationNo}/quota-details", "API-SUS-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].species").value("落叶松"))
                .andExpect(jsonPath("$[0].approvedVolume").value(40.0))
                .andExpect(jsonPath("$[0].occupiedVolume").value(0.0))
                .andExpect(jsonPath("$[0].releasedVolume").value(40.0));
    }

    @Test
    void insufficientQuotaReturns422() throws Exception {
        MvcResult seasonResult = mockMvc.perform(post("/api/seasons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"forestArea": "红山林区", "startDate": "2026-01-01", "endDate": "2026-12-31"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String seasonId = com.jayway.jsonpath.JsonPath.read(
                seasonResult.getResponse().getContentAsString(), "$.id").toString();

        mockMvc.perform(post("/api/seasons/{seasonId}/quotas", seasonId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"species": "落叶松", "authorizedVolume": 10}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/licenses/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "applicationNo": "API-002",
                                  "seasonId": %s,
                                  "workStartDate": "2026-03-01",
                                  "workEndDate": "2026-05-31",
                                  "items": [{"species": "落叶松", "volume": 40}]
                                }
                                """.formatted(seasonId)))
                .andExpect(status().isUnprocessableEntity());
    }
}
