package com.yls.activiti7demo.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yls.activiti7demo.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.activiti.api.process.model.ProcessDefinition;
import org.activiti.api.process.runtime.ProcessRuntime;
import org.activiti.api.runtime.shared.query.Pageable;
import org.activiti.api.task.runtime.TaskRuntime;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.editor.language.json.converter.BpmnJsonConverter;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.zip.ZipInputStream;

/**
 * @author joe 2022-02-15 13:17
 */
@Slf4j
@Tag(name = "流程管理")
@RestController
@RequestMapping("/activitiDemo")
@RequiredArgsConstructor
public class ActitiviDemo {

    private final ProcessRuntime processRuntime;
    private final TaskRuntime taskRuntime;
    private final SecurityUtil securityUtil;
    private final RepositoryService repositoryService;

    @Operation(description = "模型列表")
    @GetMapping("/allModels")
    public List<Model> allModels() {
        return repositoryService.createModelQuery().list();
    }

    @Operation(description = "数据库模型部署")
    @GetMapping("/deployModel")
    public Deployment deployModel(@RequestParam("modelId") String modelId) throws IOException {
        Model model = repositoryService.getModel(modelId);
        if (Objects.isNull(model)) {
            return null;
        }
        String processName = model.getName();
        if (StringUtils.hasText(processName)) {
            byte[] bytes = repositoryService.getModelEditorSource(model.getId());
            if (bytes == null) {
                log.info("部署ID:{}的模型数据为空，请先设计流程并成功保存，再进行发布", modelId);
                return null;
            }
            JsonNode modelNode = new ObjectMapper().readTree(bytes);
            BpmnModel bpmnModel = new BpmnJsonConverter().convertToBpmnModel(modelNode);
            return repositoryService.createDeployment()
                    .addBpmnModel(processName + ".bpmn", bpmnModel)
                    .name(processName)
                    .deploy();
        }

        return null;
    }

    @Operation(description = "流程定义列表")
    @GetMapping("/allProcess")
    public List<ProcessDefinition> allProcess() {
        return processRuntime.processDefinitions(Pageable.of(0, 10)).getContent();
    }

    /**
     * 上传文件部署
     */
    @PostMapping("/uploadFileAndDeployment")
    public boolean uploadFileAndDeployment(@RequestParam("processFile") MultipartFile processFile,
                                           @RequestParam(value = "processName", required = false) String processName) throws IOException {
        String originalFilename = processFile.getOriginalFilename();
        if (Objects.isNull(processName)) {
            processName = originalFilename.substring(0, originalFilename.lastIndexOf("."));
        }
        InputStream inputStream = processFile.getInputStream();
        Deployment deployment = null;
        if (originalFilename.contains(".zip")) {
            // 压缩包部署方式
            ZipInputStream zipInputStream = new ZipInputStream(inputStream);
            deployment = repositoryService.createDeployment().addZipInputStream(zipInputStream).name(processName).deploy();
        } else if (originalFilename.contains(".bpmn")) {
            // bpmn文件部署方式
            deployment = repositoryService.createDeployment().addInputStream(originalFilename, inputStream).name(processName).deploy();
        }
        return Objects.nonNull(deployment.getVersion());
    }
}
