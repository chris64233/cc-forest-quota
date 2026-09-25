package com.chris64233.cc.forestquota.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class PermitApiTest {

    @Autowired
    MockMvc mockMvc;

    private String newArea() {
        return "API-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String seasonJson(String area, String code) {
        return """
                {
                  "forestArea": "%s",
                  "seasonCode": "%s",
                  "startDate": "2026-03-01",
                  "endDate": "2026-06-30",
                  "quotas": [
                    {"species": "PINE", "authorizedVolume": 1000},
                    {"species": "FIR", "authorizedVolume": 500}
                  ]
                }
                """.formatted(area, code);
    }

    private void createSeason(String area, String code) throws Exception {
        mockMvc.perform(post("/api/seasons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seasonJson(area, code)))
                .andExpect(status().isCreated());
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        String area = newArea();
        createSeason(area, "S1");
        String approval = """
                {
                  "applicationNo": "APP-1",
                  "forestArea": "%s",
                  "seasonCode": "S1",
                  "workStartDate": "2026-03-05",
                  "workEndDate": "2026-05-05",
                  "lines": [
                    {"species": "PINE", "volume": 200},
                    {"species": "FIR", "volume": 100}
                  ]
                }
                """.formatted(area);
        mockMvc.perform(post("/api/permits/approvals")
                        .contentType(MediaType.APPLICATION_JSON).content(approval))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.lines[0].species").value("FIR"));
        mockMvc.perform(get("/api/seasons/{area}/{code}/ledger", area, "S1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotas[?(@.species=='PINE')].reservedVolume").value(200.0))
                .andExpect(jsonPath("$.quotas[?(@.species=='PINE')].availableVolume").value(800.0));
        String settlement = """
                {"lines": [
                  {"species": "PINE", "actualVolume": 150},
                  {"species": "FIR", "actualVolume": 100}
                ]}
                """;
        mockMvc.perform(post("/api/permits/APP-1/settlement")
                        .contentType(MediaType.APPLICATION_JSON).content(settlement))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SETTLED"));
        mockMvc.perform(get("/api/permits/APP-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[?(@.species=='PINE')].harvestedVolume").value(150.0))
                .andExpect(jsonPath("$.lines[?(@.species=='PINE')].releasedVolume").value(50.0));
        mockMvc.perform(get("/api/seasons/{area}/{code}/ledger", area, "S1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotas[?(@.species=='PINE')].reservedVolume").value(0.0))
                .andExpect(jsonPath("$.quotas[?(@.species=='PINE')].harvestedVolume").value(150.0))
                .andExpect(jsonPath("$.quotas[?(@.species=='PINE')].availableVolume").value(850.0));
        mockMvc.perform(get("/api/permits/APP-1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eventType").value("APPROVED"))
                .andExpect(jsonPath("$[1].eventType").value("SETTLED"))
                .andExpect(jsonPath("$[2].eventType").value("RELEASED"));
    }

    @Test
    void duplicateSeasonReturnsConflict() throws Exception {
        String area = newArea();
        createSeason(area, "S1");
        mockMvc.perform(post("/api/seasons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(seasonJson(area, "S1")))
                .andExpect(status().isConflict());
    }

    @Test
    void conflictingApprovalReturns409() throws Exception {
        String area = newArea();
        createSeason(area, "S1");
        String body = """
                {
                  "applicationNo": "APP-2",
                  "forestArea": "%s",
                  "seasonCode": "S1",
                  "workStartDate": "2026-03-05",
                  "workEndDate": "2026-05-05",
                  "lines": [{"species": "PINE", "volume": %d}]
                }
                """;
        mockMvc.perform(post("/api/permits/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(area, 10)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/permits/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(area, 10)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/permits/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted(area, 20)))
                .andExpect(status().isConflict());
    }

    @Test
    void invalidVolumePrecisionRejected() throws Exception {
        String area = newArea();
        mockMvc.perform(post("/api/seasons")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "forestArea": "%s",
                                  "seasonCode": "S1",
                                  "startDate": "2026-03-01",
                                  "endDate": "2026-06-30",
                                  "quotas": [{"species": "PINE", "authorizedVolume": 1.2345}]
                                }
                                """.formatted(area)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownPermitReturns404() throws Exception {
        mockMvc.perform(get("/api/permits/NOPE-404"))
                .andExpect(status().isNotFound());
    }
}
