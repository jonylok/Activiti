package org.activiti.spring.conformance.set3;

import org.activiti.api.model.shared.event.RuntimeEvent;
import org.activiti.api.process.model.ProcessInstance;
import org.activiti.api.process.model.builders.ProcessPayloadBuilder;
import org.activiti.api.process.model.events.BPMNActivityEvent;
import org.activiti.api.process.model.events.BPMNSequenceFlowTakenEvent;
import org.activiti.api.process.model.events.ProcessRuntimeEvent;
import org.activiti.api.process.runtime.ProcessAdminRuntime;
import org.activiti.api.process.runtime.ProcessRuntime;
import org.activiti.api.runtime.shared.query.Page;
import org.activiti.api.runtime.shared.query.Pageable;
import org.activiti.api.task.model.Task;
import org.activiti.api.task.model.builders.TaskPayloadBuilder;
import org.activiti.api.task.model.events.TaskRuntimeEvent;
import org.activiti.api.task.runtime.TaskRuntime;
import org.activiti.spring.conformance.util.RuntimeTestConfiguration;
import org.activiti.spring.conformance.util.security.SecurityUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
public class UserTaskCandidateGroupsTest {

    private final String processKey = "usertaskgr-1a8cdf77-0981-45d4-8080-7cf1a80c973b";

    @Autowired
    private ProcessRuntime processRuntime;

    @Autowired
    private TaskRuntime taskRuntime;

    @Autowired
    private SecurityUtil securityUtil;

    @Autowired
    private ProcessAdminRuntime processAdminRuntime;

    @Before
    public void cleanUp() {
        clearEvents();
    }


    @Test
    public void shouldCreateAndCompleteATaskAndDontSeeNext() {

        securityUtil.logInAs("user1");

        ProcessInstance processInstance = processRuntime.start(ProcessPayloadBuilder
                .start()
                .withProcessDefinitionKey(processKey)
                .withBusinessKey("my-business-key")
                .withName("my-process-instance-name")
                .build());

        //then
        assertThat(processInstance).isNotNull();
        assertThat(processInstance.getStatus()).isEqualTo(ProcessInstance.ProcessInstanceStatus.RUNNING);
        assertThat(processInstance.getBusinessKey()).isEqualTo("my-business-key");
        assertThat(processInstance.getName()).isEqualTo("my-process-instance-name");

        // I should be able to get the process instance from the Runtime because it is still running
        ProcessInstance processInstanceById = processRuntime.processInstance(processInstance.getId());

        assertThat(processInstanceById).isEqualTo(processInstance);

        // I should get a task for User1
        Page<Task> tasks = taskRuntime.tasks(Pageable.of(0, 50));

        assertThat(tasks.getTotalItems()).isEqualTo(1);

        Task task = tasks.getContent().get(0);

        Task taskById = taskRuntime.task(task.getId());

        assertThat(taskById.getStatus()).isEqualTo(Task.TaskStatus.CREATED);

        assertThat(task).isEqualTo(taskById);

        assertThat(task.getAssignee()).isNull();


        assertThat(RuntimeTestConfiguration.collectedEvents)
                .extracting(RuntimeEvent::getEventType)
                .containsExactly(
                        ProcessRuntimeEvent.ProcessEvents.PROCESS_CREATED,
                        ProcessRuntimeEvent.ProcessEvents.PROCESS_STARTED,
                        BPMNActivityEvent.ActivityEvents.ACTIVITY_STARTED,
                        BPMNActivityEvent.ActivityEvents.ACTIVITY_COMPLETED,
                        BPMNSequenceFlowTakenEvent.SequenceFlowEvents.SEQUENCE_FLOW_TAKEN,
                        BPMNActivityEvent.ActivityEvents.ACTIVITY_STARTED,
                        TaskRuntimeEvent.TaskEvents.TASK_CREATED);

        clearEvents();

        taskRuntime.claim(TaskPayloadBuilder.claim().withTaskId(task.getId()).build());

        assertThat(RuntimeTestConfiguration.collectedEvents)
                .extracting(RuntimeEvent::getEventType)
                .containsExactly(
                        TaskRuntimeEvent.TaskEvents.TASK_ASSIGNED,
                        TaskRuntimeEvent.TaskEvents.TASK_UPDATED);

        clearEvents();

        taskRuntime.complete(TaskPayloadBuilder.complete().withTaskId(task.getId()).build());

        assertThat(RuntimeTestConfiguration.collectedEvents)
                .extracting(RuntimeEvent::getEventType)
                .containsExactly(
                        TaskRuntimeEvent.TaskEvents.TASK_COMPLETED,
                        BPMNActivityEvent.ActivityEvents.ACTIVITY_COMPLETED,
                        BPMNSequenceFlowTakenEvent.SequenceFlowEvents.SEQUENCE_FLOW_TAKEN,
                        BPMNActivityEvent.ActivityEvents.ACTIVITY_STARTED,
                        TaskRuntimeEvent.TaskEvents.TASK_CREATED);

        // Check with user2 as he is a candidate
        securityUtil.logInAs("user2");

        tasks = taskRuntime.tasks(Pageable.of(0, 50));

        assertThat(tasks.getTotalItems()).isEqualTo(1);

        task = tasks.getContent().get(0);

        taskById = taskRuntime.task(task.getId());

        assertThat(taskById.getStatus()).isEqualTo(Task.TaskStatus.CREATED);

        List<String> candidateUsers = taskRuntime.userCandidates(task.getId());

        assertThat(candidateUsers).hasSize(0);

        List<String> candidateGroups = taskRuntime.groupCandidates(task.getId());
        assertThat(candidateGroups).contains("group2");

        // Check with user1 candidates which is not a candidate
        securityUtil.logInAs("user1");

        tasks = taskRuntime.tasks(Pageable.of(0, 50));

        assertThat(tasks.getTotalItems()).isEqualTo(0);

        // Check with user3 candidates which is a candidate
        securityUtil.logInAs("user3");

        tasks = taskRuntime.tasks(Pageable.of(0, 50));

        assertThat(tasks.getTotalItems()).isEqualTo(1);


    }


    @After
    public void cleanup() {
        securityUtil.logInAs("admin");
        Page<ProcessInstance> processInstancePage = processAdminRuntime.processInstances(Pageable.of(0, 50));
        for (ProcessInstance pi : processInstancePage.getContent()) {
            processAdminRuntime.delete(ProcessPayloadBuilder.delete(pi.getId()));
        }
        clearEvents();
    }

    public void clearEvents() {
        RuntimeTestConfiguration.collectedEvents.clear();
    }

}
