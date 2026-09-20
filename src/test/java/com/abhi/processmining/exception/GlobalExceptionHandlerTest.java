package com.abhi.processmining.exception;

import com.abhi.processmining.controller.AnalyticsController;
import com.abhi.processmining.controller.ConformanceController;
import com.abhi.processmining.controller.ImportController;
import com.abhi.processmining.service.AnalyticsService;
import com.abhi.processmining.service.ConformanceService;
import com.abhi.processmining.service.CsvImportService;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GlobalExceptionHandlerTest {

    private final MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(
                    new AnalyticsController(new AnalyticsService(null)),
                    new ConformanceController(new ConformanceService(null)),
                    new ImportController(new CsvImportService(null))
            )
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    void invalidRequestParamNamesTheParameter() throws Exception {
        mockMvc.perform(get("/api/analytics/bottlenecks").param("top", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("top: must be greater than or equal to 1"))
                .andExpect(jsonPath("$.errors[0].field").value("top"))
                .andExpect(jsonPath("$.instance").value("/api/analytics/bottlenecks"));
    }

    @Test
    void everyInvalidParamIsReported() throws Exception {
        mockMvc.perform(get("/api/analytics/bottlenecks").param("top", "51").param("minFrequency", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.length()").value(2));
    }

    @Test
    void wrongParamTypeIsProblemDetail() throws Exception {
        mockMvc.perform(get("/api/analytics/bottlenecks").param("top", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.instance").value("/api/analytics/bottlenecks"));
    }

    @Test
    void invalidBodyNamesTheListElement() throws Exception {
        mockMvc.perform(post("/api/conformance")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expected\":[\"A\",\" \"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("expected[1]"))
                .andExpect(jsonPath("$.errors[0].message").value("must not be blank"));
    }

    @Test
    void malformedJsonIsProblemDetail() throws Exception {
        mockMvc.perform(post("/api/conformance").contentType(MediaType.APPLICATION_JSON).content("{\"expected\":["))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void invalidCsvIsProblemDetail() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "e.csv", "text/csv", "foo,bar\n1,2\n".getBytes());

        mockMvc.perform(multipart("/api/import").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid CSV"))
                .andExpect(jsonPath("$.detail").value(containsString("Missing column(s)")));
    }

    @Test
    void missingFilePartIsBadRequest() throws Exception {
        mockMvc.perform(multipart("/api/import"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    @Test
    void unexpectedErrorHidesInternals() throws Exception {
        mockMvc.perform(get("/api/analytics/variants"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.title").value("Internal Server Error"))
                .andExpect(jsonPath("$.detail").value("Something went wrong on our side. The error has been logged."))
                .andExpect(content().string(not(containsString("NullPointerException"))))
                .andExpect(content().string(not(containsString("trace"))));
    }
}
