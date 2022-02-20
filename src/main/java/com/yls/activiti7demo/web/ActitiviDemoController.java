package com.yls.activiti7demo.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yls.activiti7demo.pojo.BusinessTrip;
import com.yls.activiti7demo.util.SecurityUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.activiti.api.process.model.ProcessDefinition;
import org.activiti.api.process.model.ProcessInstance;
import org.activiti.api.process.model.builders.DeleteProcessPayloadBuilder;
import org.activiti.api.process.model.builders.ProcessPayloadBuilder;
import org.activiti.api.process.model.payloads.DeleteProcessPayload;
import org.activiti.api.process.runtime.ProcessRuntime;
import org.activiti.api.runtime.shared.query.Page;
import org.activiti.api.runtime.shared.query.Pageable;
import org.activiti.api.task.model.Task;
import org.activiti.api.task.model.builders.ClaimTaskPayloadBuilder;
import org.activiti.api.task.model.builders.TaskPayloadBuilder;
import org.activiti.api.task.model.payloads.CompleteTaskPayload;
import org.activiti.api.task.runtime.TaskRuntime;
import org.activiti.bpmn.model.BpmnModel;
import org.activiti.editor.language.json.converter.BpmnJsonConverter;
import org.activiti.engine.HistoryService;
import org.activiti.engine.RepositoryService;
import org.activiti.engine.TaskService;
import org.activiti.engine.history.HistoricTaskInstance;
import org.activiti.engine.repository.Deployment;
import org.activiti.engine.repository.Model;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.zip.ZipInputStream;

/**
 * @author joe 2022-02-15 13:17
 */
@Slf4j
@Tag(name = "流程管理")
@RestController
@RequestMapping("/activitiDemo")
@RequiredArgsConstructor
public class ActitiviDemoController {

    private final ProcessRuntime processRuntime;
    private final TaskRuntime taskRuntime;
    private final SecurityUtil securityUtil;
    private final RepositoryService repositoryService;
    private final TaskService taskService;
    private final HistoryService historyService;

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

    @Operation(description = "删除所有流程定义列表")
    @DeleteMapping("/allProcess")
    public List<ProcessDefinition> deleteAllProcess() {
        List<Deployment> list = repositoryService.createDeploymentQuery().list();
        list.forEach(deployment -> {
            repositoryService.deleteDeployment(deployment.getId(), true);
        });
        return processRuntime.processDefinitions(Pageable.of(0, 10)).getContent();
    }

    @Operation(description = "创建流程实例并完成审批单填写")
    @PostMapping("/startProcess")
    public ProcessInstance startProcess(@RequestBody BusinessTrip businessTrip) {
        org.activiti.engine.repository.ProcessDefinition processDefinition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(businessTrip.getProcessDefinitionId())
                .latestVersion()
                .singleResult();
        if (Objects.isNull(processDefinition)) {
            log.error("该流程定义不存在，请检查流程定义ID:{}", businessTrip.getProcessDefinitionId());
            return null;
        }
        String user = businessTrip.getUser();
        securityUtil.logInAs(user);
        Map<String, Object> variables = new HashMap<>();
        variables.put("businessTrip", businessTrip);
        // 新建流程实例
        ProcessInstance processInstance = processRuntime.start(
                ProcessPayloadBuilder
                        .start()
                        .withProcessDefinitionKey(processDefinition.getKey())
                        .withName(user + "的出差申请流程")
                        .withVariables(variables)
                        .build());

        // 完成当前流程实例中填写审批单的任务
        org.activiti.engine.task.Task userTask = taskService.createTaskQuery()
                .processInstanceId(processInstance.getId())
                .taskAssignee(user)
                .singleResult();
        if (Objects.nonNull(userTask)) {
            String userTaskId = userTask.getId();
            log.info("完成当前流程实例中填写审批单的任务，任务ID:{}", userTaskId);
            taskService.setVariable(userTaskId, "businessTrip", businessTrip);
            CompleteTaskPayload completeTaskPayload = TaskPayloadBuilder.complete()
                    .withVariables(variables)
                    .withTaskId(userTaskId)
                    .build();
            taskRuntime.complete(completeTaskPayload);
        }
        return processInstance;
    }

    @Operation(description = "流程实例流程历史记录")
    @GetMapping("/processHistory")
    public List<HistoricTaskInstance> processHistory(@RequestParam String processInstanceId) {
        List<HistoricTaskInstance> historicTaskInstances = historyService.createHistoricTaskInstanceQuery()
                // .includeProcessVariables()
                // .includeTaskLocalVariables()
                .processInstanceId(processInstanceId)
                .list();
        log.info("流程实例:{}的历史记录:{}", processInstanceId, historicTaskInstances);
        return historicTaskInstances;
    }

    @Operation(description = "当前用户流程实例列表")
    @GetMapping("/currentUserProcess")
    public List<ProcessInstance> currentUserProcess(@RequestParam String username) {
        // TODO 所有用户都能看到所有流程实例？？？
        securityUtil.logInAs(username);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Page<ProcessInstance> processInstancePage = processRuntime.processInstances(Pageable.of(0, 10));
        log.info("当前用户:{}的流程实例:{}", authentication.getName(), processInstancePage.getContent());
        return processInstancePage.getContent();
    }

    @Operation(description = "删除当前用户所有流程列表")
    @DeleteMapping("/deleteCurrentUserProcess")
    public List<ProcessInstance> deleteCurrentUserProcess(@RequestParam String username) {
        // TODO 所有用户实例都被删除了？？？
        securityUtil.logInAs(username);
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Page<ProcessInstance> processInstancePage = processRuntime.processInstances(Pageable.of(0, 10));
        List<ProcessInstance> content = processInstancePage.getContent();
        for (ProcessInstance processInstance : content) {
            DeleteProcessPayloadBuilder deleteProcessPayloadBuilder = new DeleteProcessPayloadBuilder();
            DeleteProcessPayload build = deleteProcessPayloadBuilder.withProcessInstanceId(processInstance.getId()).build();
            processRuntime.delete(build);
        }
        Page<ProcessInstance> processInstancePage1 = processRuntime.processInstances(Pageable.of(0, 10));
        List<ProcessInstance> content1 = processInstancePage1.getContent();
        log.info("删除当前用户:{}的流程实例之后:{}", authentication.getName(), content1);
        return content1;
    }

    @Operation(description = "当前用户任务列表")
    @GetMapping("/currentUserTasks")
    public List<Task> currentUserTasks(@RequestParam String username) {
        securityUtil.logInAs(username);
        Page<Task> tasks = taskRuntime.tasks(Pageable.of(0, 10));
        return tasks.getContent();
    }

    @Operation(description = "处理用户任务")
    @GetMapping("/handleUserTasks")
    public List<Task> handleUserTasks(@RequestParam String username,
                                      @RequestParam String taskId) {
        securityUtil.logInAs(username);
        Task task = taskRuntime.task(taskId);
        if (Objects.isNull(task)) {
            log.info("任务不存在，任务ID:{}", taskId);
            return Collections.emptyList();
        }
        log.info("当前用户:{}的任务详情:{}", username, task);
        // 查询当前用户是否为任务的办理人
        org.activiti.engine.task.Task userTask = taskService.createTaskQuery()
                .taskId(taskId)
                .active()
                .singleResult();
        if (Objects.isNull(userTask)) {
            log.info("可执行任务不存在，任务ID:{}", taskId);
            return Collections.emptyList();
        }
        // 如果任务的办理人为空，则认领任务
        if (Objects.isNull(userTask.getAssignee())) {
            // 领取任务
            taskRuntime.claim(new ClaimTaskPayloadBuilder().withTaskId(taskId).build());
        }
        // 设置流程所需参数
        String userTaskId = userTask.getId();
        taskService.setVariable(userTaskId, "approved", true);
        // 审批通过
        CompleteTaskPayload taskPayload = TaskPayloadBuilder.complete()
                .withVariable("approved", true)
                .withTaskId(userTaskId)
                .build();
        taskRuntime.complete(taskPayload);

        Page<Task> tasks1 = taskRuntime.tasks(Pageable.of(0, 10));
        log.info("当前用户:{}的任务1:{}", username, tasks1.getContent());
        return tasks1.getContent();
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
