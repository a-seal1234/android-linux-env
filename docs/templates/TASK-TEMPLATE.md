# Task Template — Dev Framework v0.2

ใช้ร่วมกับ [Specification](../dev-framework/DEV-FRAMEWORK-v0.2.md) ปรับระดับรายละเอียดตาม risk ช่องที่ไม่เกี่ยวข้องละได้ แบบนี้เป็นแม่แบบสำหรับนำไปใช้ ไม่ใช่การแก้ policy ของ repository ปัจจุบัน

หลัง adopt ให้เก็บ template ที่ `docs/templates/TASK-TEMPLATE.md` แยกจาก active tasks แล้วใช้เนื้อหาแบบสั้น/ขยายสร้าง `docs/tasks/<TASK-ID>/TASK.md` ตัวไฟล์ template ปัจจุบันยังคง path เดิมเพื่อ compatibility ตาม migration mapping ใน Specification ข้อ 17 ไม่ได้ย้ายหรือสร้าง template คู่ขนานในรอบนี้

Task Directory มี TASK.md เป็นหลัก; evidence/ และ HANDOFF.md สร้างเมื่อมีรายละเอียดที่ควรแยกไฟล์เท่านั้น ไม่มี TASKS.md/บอร์ดย่อยภายใน directory Workflow status มีผลที่ central board เดียว; TASK.md เก็บรายละเอียด/evidence และ request ไม่เก็บ status mirror

## 1. แบบสั้นสำหรับ Trivial

```text
TASK-ID — Outcome
Board: ../../../TASKS.md + entry reference (relative from docs/tasks/TASK-ID/TASK.md)
Owner / Harness:
Repo / Branch / Workspace / Base:
Risk: Trivial — เหตุผล
Authorized access: เลือก Read | Extend | Modify | Full ตาม resource + operation;
                   authority/reference (ระบุเฉพาะสิทธิ์ที่ใช้)
                   รวมสิทธิ์ TASK.md ของตน; source/board เป็นคนละ scope
Acceptance / Verification: สิ่งที่ต้องผ่านและวิธีตรวจ
Preflight (เมื่อเกี่ยวข้อง): แผน/สิ่งที่รักษา, collision Clear|Conflict|Unknown,
                           planning Ready|Needs Revision|Blocked; checked by/ref
Actual / Evidence (review/close): task-local diff + untracked inspection/snapshot
          หรือ no mutation; deviations/contracts เมื่อมี; revision/input coverage,
          procedure/environment, verdict/exit status/ผลที่สังเกตได้, limitations
Review: Self-review หรือ reviewer/reference ตามที่ใช้จริง
Next / Blocker: ถ้ามี; ระบุ unblock condition
Board update (เมื่อจะเปลี่ยน status): request + current board/revision/evidence ref
                   หรือใช้สิทธิ์ temporary Board Writer ตาม protocol
```

Mandatory ก่อนเริ่ม: ID/outcome, board link, owner/harness, location/revision context, risk, authorized scope, criteria และวิธีตรวจ Mandatory ก่อน Done: evidence ตรง revision และ review ตามระดับที่กำหนด ทุกอย่างเก็บใน TASK.md ไฟล์เดียวได้พร้อม summary entry ในบอร์ด ไม่บังคับ evidence/HANDOFF แยกไฟล์ การมี scope ของ Task ไม่ให้สิทธิ์เขียนบอร์ดอัตโนมัติ

Preflight รวม planning self-validation และ collision check ตามสิ่งที่เกี่ยวข้องได้โดยไม่ต้องกรอกแยกทุกหัวข้อ งานเพิ่มไฟล์อย่างเดียวใช้ Extend ของ path/pattern ได้ ไม่ต้องขอ Modify ไฟล์เดิม หากต้องแก้ไฟล์ที่ Task สร้างเองต่อ ให้ระบุ conditional Modify ของไฟล์ใหม่นั้นไว้ใน scope ล่วงหน้า Ownership ไม่ให้สิทธิ์เอง Full ใช้เฉพาะ operation ที่อนุญาตและไม่เปลี่ยนระดับ risk/review โดยอัตโนมัติ ช่อง Actual กรอกเมื่อมีงานจริงหรือตอนตรวจรับ ไม่ต้องเตรียม file list ก่อนเริ่ม

## 2. แบบขยายสำหรับ Standard ขึ้นไป

```text
TASK-ID — Outcome
Board entry: ../../../TASKS.md + entry reference
Task Directory: docs/tasks/TASK-ID/ (repository-relative)
Owner / Harness / Session:
Repo / Branch / Workspace or Worktree / Base:
Risk / Rationale:
Source requirements / contracts:

Impact plan:
  Capability:
  Planned new resources / bounded patterns:
  Planned existing modifications:
  Preserved behavior/contracts / protected resources:
  Existing file owners / active writers:
  Logical resources / owners / versions:
  Dependencies / parallel opportunities / waits:

Authorized access:
  Read:
  Extend: <expected new resources only>
  Modify: <existing authorized files and/or explicitly authorized
           files created and registered as owned by this Task>
  Full: <only if needed; list delete/move operations explicitly>
  Granted by / authority / scope decision reference:
  Task documentation scope: Read/Extend/Modify/Full แยกตาม resource ใน directory
  Central board writer: authority/ช่วงเวลาที่ได้รับ หรือใช้ Board Update Request

Pre-write collision (before new-file/shared-resource writes):
  Paths / active writers / logical resources / generated outputs:
  Workspace / branch context / checked reference:
  Result: Clear | Conflict | Unknown
  Resolution or limitations:

Planning validation (before implementation):
  Result: Ready | Needs Revision | Blocked
  Checked by / evidence or decision reference:
  Revisions needed / blocker + unblock condition, if any:

Acceptance criteria: AC-1, AC-2, ...
Verification plan: criteria → checks/procedures/environment
Review requirement: independence level and required review gate
Integration: integrator / target / ordering / post-integration checks
Gates: implementation, verification, review; integration/release if relevant
       Pending | Complete | Blocked | Not Applicable (+ reason/revision)

Actual changes (at review/close; use diff/artifact instead of duplicate lists):
  Created / Modified / Deleted or moved:
  Deviations from plan and authorized scope / decisions:
  Logical contract changes, if any:
  Task-local diff / untracked inspection / revision-snapshot reference:
Evidence references: record revision/input coverage + checks + verdicts
Known limitations / not-run checks:
Next action / blocker / unblock condition:
Board Update Request reference, when applicable:
```

**Mandatory ตามเงื่อนไข:** Existing ownership/new paths/logical resources ต้องกรอกเมื่อมีผลต่อ scope; Integration ต้องระบุเมื่อมีการส่งมอบข้าม branch/workspace; High Risk/Critical ต้องมี contract impact, failure cases และ review requirement; Critical ต้องมี independent review gate และ recovery/rollback plan

Planning validation ตรวจเก้าประเด็นตาม Specification ข้อ 4.2 และ self-validate ได้ใน Trivial/Standard; High Risk/Critical ใช้ review policy/authority เดิม ไม่มี reviewer ใหม่ที่บังคับทุก Task Collision check ใช้เมื่อสร้างไฟล์ใหม่หรือเขียน shared resource และอ้าง preflight เดิมได้ถ้ายังครอบคลุม ไม่ต้องสร้างรายงานแยกหรืออัปเดตทุกครั้งที่บันทึก

Fields เหล่านี้เป็น conditional metadata ตามช่วงงาน ข้อที่ไม่เกี่ยวข้องละหรือรวมกับ scope/evidence เดิมได้ Planning/collision result ไม่ใช่ Task Status และไม่ให้ permission ถ้ายังไม่ได้ตรวจ ให้ระบุ execution เป็น Pending/Not Run และยังไม่มี result แทนสร้างผล Ready/Clear Actual changes ไม่ต้องกรอกล่วงหน้า; ตอน review/close ต้องตรวจเทียบจริง แต่อ้าง diff/artifact ได้ การเพิ่มใน authorized pattern ที่ไม่ชน contract อัปเดต Actual ได้โดยไม่ขอซ้ำ ส่วน out-of-scope ต้องใช้ Scope Change ก่อนทำส่วนนั้นต่อ

**Optional จนกว่าจะจำเป็น:** Epic/priority, Last Activity, ownership lease, recovery state, pending changes และ handoff ผู้ส่งต่อหรือรับคืนต้องกรอก revision/workspace/pending changes/exact next action ก่อนดำเนินการ รวม pending Board Update Request; ไม่บังคับมี lease ในงานธรรมดา

บอร์ดเก็บเพียง ID/outcome/status/owner-harness/risk กับ dependency/blocker/gate/revision summary ที่เกี่ยวข้อง ลิงก์ถึง TASK.md; ไม่คัดลอก criteria/logs/notes ขึ้นบอร์ด Owner ทำงานใน directory ของตนได้ตาม scope Reviewer อ่านหรือเขียนเฉพาะ review artifact ที่ได้รับสิทธิ์ ไม่ได้ own directory อื่นโดยอัตโนมัติ Task ที่ Done ต้องยังตาม evidence ได้

## 3. Board Update / Scope Change / Evidence / Handoff แบบย่อ

```text
Board Update Request (ใน TASK.md; ไม่ต้องสร้างไฟล์แยก):
  Task / request reference / requested summary or status change:
  Expected current board entry + revision/snapshot:
  Task workspace/branch/revision + evidence/gate references:
  Reason / blocker or scope authority reference, if relevant:
  Request delivery: Pending หรือ acknowledgement reference หลังอัปเดตจริง
  [คำขอและ acknowledgement ไม่ใช่ workflow status หรือ verification evidence]

Scope change:
  Reason / requested paths + operations / logical impact:
  Current owner / additive alternatives / added verification:
  Decision: Pending | Approved | Rejected
  Decided by / authority / decision reference:
  Updated task scope / effective revision:

Evidence E-ID:
  Task / criteria / repo:
  Commit or base + snapshot identity / inputs covered:
  Command or procedure / environment:
  Exit status (N/A with reason for manual checks):
  Verdict: Passed | Failed | Blocked | Not Run | Not Applicable
  Result / expected failure cases / known limitations:
  Artifact reference:
  Reviewer / independence / review result, if required:

Handoff / Recovery:
  From → To / reassignment authority:
  Repo / workspace / branch / last commit:
  Active writer check / last activity, if relevant:
  Staged / unstaged / untracked changes + snapshot reference:
  Current access + resource ownership transferred:
  Valid evidence / stale checks / gates pending:
  Central board reference / pending Board Update Requests:
  Task Directory ownership/access transferred / documentation integration plan:
  Blocker + unblock condition / exact next action:
```

การ approved scope ไม่ใช่การอนุมัติ network write หรือ destructive history operation ที่อยู่นอก authority; ตรวจคำสั่งที่มีผลจริงในแต่ละโครงการด้วย

Board Writer ตรวจ latest entry/revision/evidence/gates และ writer reservation ก่อนแก้เฉพาะ summary จากบอร์ดล่าสุด คำขอยังไม่มีผลจนบอร์ดเปลี่ยน Owner ตรวจสถานะจริงและ reconcile request หลังอัปเดต ถ้าหยุดกลางทางให้ผู้รับตรวจทั้งบอร์ด/task directory/dirty workspace ก่อนทำต่อ ห้าม overwrite รายการอื่นจาก snapshot เก่า Coordinator จะเขียน acknowledgement ลง directory ได้เฉพาะเมื่อมี scope ไม่ใช่เพราะเป็น Board Writer

Evidence สั้นเก็บใน TASK.md หรือใช้ `evidence/`/external artifact ที่เข้าถึงได้ พร้อม revision ตามเดิม Handoff สั้น inline ได้ หรือใช้ HANDOFF.md เมื่อจำเป็น การโอน directory ต้องระบุ authority และ pending changes; permission ของ source/board ไม่เกิดตาม ownership อัตโนมัติ

## 4. ตัวอย่าง Task ที่กรอกแล้ว

**ตัวอย่างสมมติทั้งหมด — ไม่มี approval, commit หรือ test result จริงในตัวอย่างนี้**

### TASK-P03 — Save/Reopen adapter สำหรับ stroke prototype

- **Task file context:** เนื้อหานี้สำหรับ `docs/tasks/TASK-P03/TASK.md`; workflow status ดูจาก central board example ด้านล่าง ไม่เก็บ Status อีกชุดในไฟล์ Task
- **Owner / Harness:** Agent-Storage / local shell session S3
- **Repo / Branch / Worktree / Base:** prototype repo; `codex/p03-storage`; `/work/p03`; baseline B0 (ตัวแทน SHA ที่ต้องแทนด้วยค่าจริงตอนใช้)
- **Risk:** High Risk — เขียนไฟล์โปรเจกต์และอาจกระทบข้อมูลที่ผู้ใช้เข้าใจว่าบันทึกแล้ว
- **Source / preserved behavior:** persistence contract S1; save ไม่สำเร็จต้องรายงาน failure และรักษา snapshot สมบูรณ์เดิม; ไม่เปลี่ยน input/rendering semantics
- **Planned new resources:** คาดว่าใช้ `extensions/stroke_store.rs`, `extensions/stroke_store_checks.rs`; เสนอ bounded pattern `extensions/stroke_store*.rs` สำหรับ helper/checks เพิ่มเติมที่ใช้ D1/S1 เดิม
- **Planned existing modifications / protected resources:** ไม่มี; คง `core/document.rs`, `app/registry.rs` และ D1/S1 ไว้
- **Existing ownership:** `core/document.rs` ของ Agent-Document; `app/registry.rs` ของ Integrator; read-only สำหรับ Task นี้
- **Logical resources:** format S1 และ document snapshot contract D1 ของ Owner-Storage; เพิ่ม format field/version ไม่ได้ใน scope นี้
- **Proposed access — ยังรอ authority:** Read contracts/core; Extend เฉพาะ resource ใหม่ใน `extensions/stroke_store*.rs`; conditional Modify เฉพาะไฟล์ใน pattern นี้ที่ Task สร้างและลงทะเบียนเป็นเจ้าของแล้ว ไม่รวมไฟล์ที่มีอยู่ก่อนหรือคนอื่นสร้าง; ไม่มี Full
- **Proposed documentation access:** Extend TASK-P03/TASK.md และ task-owned `.md` artifacts ใต้ TASK-P03/evidence/ เมื่อจำเป็น พร้อม conditional Modify ของไฟล์เหล่านั้น; directory ของ Task อื่นและ root TASKS.md ไม่อยู่ใน scope นี้ Reviewer ต้องมี scope ของ artifact ที่จะเขียนแยก
- **Scope authority:** Pending — ต้องมี authority reference จริงจาก project owner หรือ Owner-Storage ที่ได้รับมอบอำนาจ ไม่มี decision/approval ที่เกิดขึ้นจริงในตัวอย่างนี้
- **Additive scope:** implement adapter หลัง interface D1/S1; ยังไม่แก้ registry; Integrator รับงาน registration แยกใน TASK-P05
- **Dependencies:** contract D1/S1 ต้อง approved; ทำขนานกับ renderer ที่ใช้ contract version เดียวกันได้

Pre-write collision / planning validation:

- Collision execution: Not Run; Result: ยังไม่มี ต้องตรวจ path/pattern, active writers, D1/S1 semantics, generated outputs และ workspace/branch จริงก่อนเขียน
- Planning execution: Not Run; Result: ยังไม่มี ต้องตรวจ criteria/access/owners/contracts/dependencies/isolation/integration/verification ตามข้อ 4.2 พร้อม review policy สำหรับ High Risk
- ถ้าพบ path/writer conflict ให้ resolution ก่อนเขียน; ถ้าข้อมูล shared contract ไม่พอให้ Unknown และตรวจเพิ่ม/พักส่วนนั้น ห้ามสรุป Clear/Ready จากแผนตัวอย่าง
- Checked by / evidence / decision reference: Pending — ไม่มีผลตรวจสมมติที่นำไปใช้เป็นหลักฐานจริงได้

Acceptance:

1. AC-1: Save/Reopen คืน stroke IDs, ลำดับ, positions และ pressure ตาม representation ที่ S1 กำหนด
2. AC-2: Interrupted write ไม่แทนที่ snapshot สมบูรณ์เดิมและไม่รายงาน success
3. AC-3: Malformed input ถูกปฏิเสธโดยไม่เปลี่ยน document ที่เปิดอยู่
4. AC-4: Actual Changes อยู่ใน scope ที่ authority อนุมัติและ task-owned files ของ pattern; deviations จากแผนมี reference อธิบาย ไม่เปลี่ยน D1/S1 หรือ registry โดยไม่ได้รับอนุญาต

Verification plan:

- AC-1: Round-trip sample ที่มี pressure variation และหลาย stroke; เปรียบเทียบตาม schema S1
- AC-2: Inject write failure ก่อน publish; ตรวจ previous snapshot และ failure result
- AC-3: Load malformed sample ใน isolated test workspace; เปรียบเทียบ active document ก่อน/หลัง
- AC-4: Reviewer เทียบ Planned/Authorized/Actual โดยใช้ diff และ untracked inspection ของ revision จริง; เทียบ pre-task snapshot หากไฟล์ untracked อยู่ก่อน ตรวจ deleted/moved paths และ resource contract ด้วย
- บันทึก command จริง, environment, revision/snapshot และ exit status เมื่อรัน ไม่ใส่คำว่า Passed ล่วงหน้า

Review / integration:

- Reviewer เป็น agent/session แยกที่ไม่ได้ร่วมเขียน adapter; อ่าน diff และ evidence โดยตรง
- หากไม่มี reviewer ให้บันทึก limitation และรอ High Risk decision ตาม policy ก่อนปิด review gate
- Integrator รวม TASK-P03 หลัง contract แล้วทำ TASK-P05 registration; ตรวจ end-to-end บน integrated SHA
- Task นี้มี acceptance แบบ branch-local จึง Done ได้เมื่อ AC/review ผ่าน แต่ Integration ยัง Pending และยัง Release Ready ไม่ได้

Current gates / evidence:

- Implementation / Verification / Review / Integration: Pending
- AC-1–AC-4: Not Run; ยังไม่มี artifact หรือ claim ว่าผ่าน
- **Actual changes:** Pending — ยังไม่ได้ implement; Created/Modified/Deleted/Moved/Deviations/Logical changes และ diff/revision reference ยังไม่มี ห้ามนำ planned paths ไปกรอกเหมือนสร้างแล้ว
- Next: ขอ/ตรวจ authority และ D1/S1 จริง จากนั้นทำ planning validation และ pre-write collision ก่อนเริ่ม adapter
- สิ่งที่รอในแผนตัวอย่าง: scope authority/contract readiness และผล preflight ยังไม่ยืนยัน; ไม่ได้อ้างว่ามี Task runtime ที่ Blocked จริง

Board update: ยังไม่มี request ที่เกิดขึ้นจริง เมื่อพร้อมให้ส่ง registration/claim request พร้อม task revision และ authority/validation references; Board Writer ตรวจแล้วจึงเปลี่ยน central status ห้ามถือว่าเขียนคำขอ In Progress หรือ Done แล้วเป็นสถานะจริง

### Central Board Entry / Directory ของตัวอย่างเดียวกัน

ตัวอย่าง Markdown นี้มี context เป็น root TASKS.md ของ repository สมมติ ไม่ใช่สถานะ/ผลตรวจจริง:

```markdown
| ID | Task | Status | Owner / Harness | Risk | Dependencies / Gates |
|---|---|---|---|---|---|
| [TASK-P03](docs/tasks/TASK-P03/TASK.md) | Save/Reopen adapter | Planned | Agent-Storage / local shell S3 | High Risk | Scope/contract/planning pending; checks Not Run |
```

```text
docs/tasks/TASK-P03/
└── TASK.md                          # เนื้อหาตัวอย่างด้านบน; inline evidence/request ได้
# เมื่อมี artifact จริงจึงเพิ่ม evidence/verification.md หรือ evidence/review.md
# เมื่อมีรายละเอียด handoff จริงจึงเพิ่ม HANDOFF.md
```

จาก TASK.md ลิงก์บอร์ดคือ `../../../TASKS.md`; จาก `evidence/verification.md` ไปบอร์ดคือ `../../../../TASKS.md` และกลับไป Task คือ `../TASK.md` ตัวอย่าง source path เช่น `extensions/stroke_store.rs` เป็น repository-relative ไม่ใช่ relative กับ task directory ต้องแปลงก่อนใช้เป็น Markdown link

### การเดินงานต่อเมื่อเงื่อนไขพร้อม — ตัวอย่างเชิงเงื่อนไข ไม่ได้รันจริง

1. **ก่อนเริ่ม:** เมื่อ authority อนุมัติ pattern และ conditional Modify จริงแล้ว เก็บ reference/validation ใน TASK.md และส่ง request ให้ Board Writer ลงทะเบียน/claim ตาม lifecycle ผู้เขียนตรวจว่า In Progress ถูกบันทึกใน central board ก่อนเริ่มส่วน implementation; Needs Revision/Blocked เป็น planning result ไม่ใช่การเปลี่ยนสถานะบอร์ดเอง
2. **เพิ่มภายใน pattern:** หากระหว่างทำต้องเพิ่ม `extensions/stroke_store_codec.rs` ให้ตรวจ path/writer/logical collision ของไฟล์เพิ่ม เมื่อ Clear และยังใช้ D1/S1 เดิมให้สร้างได้โดยไม่ขอ approval ซ้ำ พัฒนาต่อด้วย conditional Modify เดิมและอัปเดต Actual Changes; นี่คือความต่างจากรายชื่อคาดไว้ภายในสิทธิ์ ไม่ใช่ scope escalation
3. **ขอแก้ของ owner อื่น:** หากต้องแก้ `core/document.rs` ให้ยื่น Scope Change พร้อมเหตุผล impact ต่อ D1 และ alternative รอ owner/authority หรือส่งให้ owner ลงมือก่อนแก้ส่วนนั้น ทำงานอิสระต่อได้ ไม่สร้าง document model สำเนาเพื่อเลี่ยง owner
4. **ตอน review/close:** อ้าง task-local diff รวม untracked files และ snapshot; แยก files ที่สร้าง/แก้/ลบ/ย้ายจริงและ logical changes เมื่อมี ถ้า diff มี `app/registry.rs` ซึ่งยังไม่ได้สิทธิ์ ให้รักษางานไว้ หยุดส่วนนั้น และส่ง scope request/owner review ก่อนทำต่อหรือรวมงาน ไม่รายงานว่า AC-4 ผ่าน
5. **Board sync/recovery:** เมื่อ criteria/checks ผ่านจริง ให้ส่ง Done request พร้อม revision-bound evidence Writer ตรวจจากต้นทางและอัปเดต summary เท่านั้น ถ้า session หายก่อนอัปเดต ผู้รับยังเห็นสถานะเดิม ต้องรับ ownership ตรวจ dirty changes/evidence และ pending request ก่อนทำต่อ; ถ้าเปลี่ยนบอร์ดแล้วให้อ้าง latest entry เพื่อไม่ใช้คำขอซ้ำ

ทุกขั้นยังเป็น procedure ที่เสนอ ผล Clear/Ready/Passed, approvals, actual file changes และ commit identities ต้องมาจากการทำจริงภายหลัง
