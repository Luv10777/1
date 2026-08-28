"""
工作流监控服务

实时监控工作流执行状态
"""
from typing import Dict, Any, List
from loguru import logger


class WorkflowMonitor:
    """工作流监控器"""

    def __init__(self):
        self.active_workflows = {}

    def track_workflow(self, workflow_run_id: int, project_id: int):
        """追踪工作流"""
        logger.info(f"开始追踪工作流: runId={workflow_run_id}, projectId={project_id}")

        self.active_workflows[workflow_run_id] = {
            "project_id": project_id,
            "status": "RUNNING",
            "current_step": None,
            "start_time": None,
            "steps_completed": 0,
            "total_steps": 15
        }

    def update_step(self, workflow_run_id: int, step_name: str):
        """更新当前步骤"""
        if workflow_run_id in self.active_workflows:
            self.active_workflows[workflow_run_id]["current_step"] = step_name
            self.active_workflows[workflow_run_id]["steps_completed"] += 1

            progress = (self.active_workflows[workflow_run_id]["steps_completed"] /
                       self.active_workflows[workflow_run_id]["total_steps"] * 100)

            logger.info(f"工作流进度更新: runId={workflow_run_id}, step={step_name}, progress={progress:.1f}%")

    def complete_workflow(self, workflow_run_id: int):
        """完成工作流"""
        if workflow_run_id in self.active_workflows:
            self.active_workflows[workflow_run_id]["status"] = "COMPLETED"
            logger.info(f"工作流完成: runId={workflow_run_id}")

    def fail_workflow(self, workflow_run_id: int, error: str):
        """工作流失败"""
        if workflow_run_id in self.active_workflows:
            self.active_workflows[workflow_run_id]["status"] = "FAILED"
            self.active_workflows[workflow_run_id]["error"] = error
            logger.error(f"工作流失败: runId={workflow_run_id}, error={error}")

    def get_status(self, workflow_run_id: int) -> Dict[str, Any]:
        """获取工作流状态"""
        return self.active_workflows.get(workflow_run_id, {})

    def list_active_workflows(self) -> List[Dict[str, Any]]:
        """列出所有活跃工作流"""
        return [
            {"run_id": run_id, **info}
            for run_id, info in self.active_workflows.items()
            if info["status"] == "RUNNING"
        ]
