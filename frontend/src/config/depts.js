/**
 * 部门基础数据（联调用）。
 *
 * <p>后端这 37 个接口里没有「部门列表」这一条：I-03 只返回当前用户自己所在的部门，
 * 而归因钻取（F-02 必须传 deptId）和预算维护（H-03 要选部门）都需要完整的部门清单。
 * 与其在页面里写死字面量，统一放这里，值与 travel_server.dept 表的初始化数据一致：
 *
 * <pre>
 * id  dept_name  monthly_budget  standard_level  leader_id
 *  1  销售部      85000.00        二级             2（戴庆豪）
 *  2  采购部      50000.00        三级             4（管理层）
 *  3  技术部      40000.00        二级             4（管理层）
 *  4  财务部      10000.00        二级             4（管理层）
 * </pre>
 *
 * <p>2026-09 更新：后端补了 GET /config/depts（接口见 api/index.js 的 deptList），
 * 管理层的「部门预算」页已经换成读接口，部门是增删改之后页面上的清单跟着库走。
 * 这份常量降级为「后端没起来 / mock 未覆盖」时的兜底，别再往这里加新部门了。
 */

/** deptId 与前端各页面 deptId 参数同名，别改成 id，否则 H-03 传参会 1001 */
export const DEPT_SEED = [
  { deptId: 1, deptName: '销售部', monthlyBudget: 85000, standardLevel: '二级', leaderId: 2, approverName: '戴庆豪', usedBudget: 0 },
  { deptId: 2, deptName: '采购部', monthlyBudget: 50000, standardLevel: '三级', leaderId: 4, approverName: '管理层', usedBudget: 0 },
  { deptId: 3, deptName: '技术部', monthlyBudget: 40000, standardLevel: '二级', leaderId: 4, approverName: '管理层', usedBudget: 0 },
  { deptId: 4, deptName: '财务部', monthlyBudget: 10000, standardLevel: '二级', leaderId: 4, approverName: '管理层', usedBudget: 0 }
]

/** 拷贝一份，避免页面直接改到常量本身（预算页改完会就地更新 usedBudget） */
export const deptSeed = () => DEPT_SEED.map((d) => ({ ...d }))
