# Dev Framework v0.2

**สถานะ:** Draft for adoption — เอกสารข้อกำหนดที่นำไปใช้แบบ manual ได้

**ที่มา:** ปรับต่อจาก Dev Framework v0.1 ในบทสนทนา (writing block `60318`) ตาม revision brief ของผู้ใช้ ไม่ได้เปลี่ยนกติกาที่มีผลอยู่ใน repository นี้โดยอัตโนมัติ

**ขอบเขต:** Human Developer และ AI Coding Agent หลายตัว หลาย harness และหลาย workspace ใช้ได้กับ repository เดี่ยว หลาย repository และหลาย worktree โดยไม่ผูกกับภาษา stack สถาปัตยกรรม หรือ provider

> Plan before modification. Extend without overwriting. Verify before completion. Preserve work across handoffs.

> One Board for coordination, one directory per Task for execution and evidence, one authorized writer per shared resource.

## 1. เป้าหมายและหลักการที่สืบต่อจาก v0.1

แบ่งเป้าหมายใหญ่ให้เป็นงานที่ทำ ตรวจรับ และส่งต่อได้ โดยรู้ว่าใครรับผิดชอบ แก้ส่วนใดได้ และหลักฐานใดรองรับผลลัพธ์ Framework ต้องทำงานได้ด้วยเอกสารและ Git โดยไม่ต้องมี orchestrator

หลักการหลัก:

1. บอร์ดงานเป็นแหล่งสถานะ workflow หลัก และต้องอัปเดตให้ตรงกับการทำงานจริง
2. ทุกงานมีผลลัพธ์และ acceptance criteria ที่ตรวจได้ก่อนเริ่ม implementation
3. วางแผนผลกระทบและขอบเขตการแก้ก่อนลงมือ รักษาพฤติกรรมที่อยู่นอกงาน
4. ภายใน shared working tree หนึ่งไฟล์มี active writer หลักคนเดียวต่อช่วงเวลา
5. ปรับระดับการตรวจตามความเสี่ยงและความซับซ้อน
6. ใช้หลักฐานของ revision ที่ตรวจจริงในการปิดงาน
7. รักษางานเดิม ตรวจสภาพ workspace ก่อนแก้ และส่งต่อให้ทำงานต่อได้

ใช้สิทธิ์ที่ได้รับภายในขอบเขตได้อย่างอิสระ ไม่ต้องขออนุมัติทุกการแก้ไข การควบคุมเพิ่มเติมเกิดเมื่อมี ownership conflict, เปลี่ยน contract, ขยาย scope หรือมี integration/data risk

## 2. โครงสร้างงานและบทบาท

**Initiative** ระบุเป้าหมายระดับโครงการ ขอบเขต/non-goals ความเสี่ยง และเกณฑ์ไปต่อหรือหยุด **Epic** รวมความสามารถที่ส่งมอบเป็นช่วงได้ พร้อม dependency และเงื่อนไขจบ **Task** เป็นหน่วยหลักที่มีเจ้าของและตรวจรับได้ **Subtask** ใช้เมื่อแยกผลส่งมอบ ผู้รับผิดชอบ หรือ verification ได้จริง งานเล็กใช้ Task เดียวพร้อม checklist ใน `docs/tasks/<TASK-ID>/TASK.md` ได้ หาก subtask มี Task ID/owner/workflow ของตนเอง ให้ลงทะเบียนบอร์ดและมี directory ของตนเช่นเดียวกับ Task

Task ที่เป็นการทดลองต้องระบุคำถามที่ต้องการพิสูจน์และวิธีสรุปผล การค้นพบว่าแนวทางใช้ไม่ได้อาจเป็นผลลัพธ์ที่ถูกต้องของงานทดลองได้ ถ้ามีหลักฐานตาม criteria

| บทบาท/ข้อมูล | ความรับผิดชอบ |
|---|---|
| Task / Agent Owner | รับผิดชอบผลส่งมอบและสถานะ Task ระบุ session หรือ agent identity เมื่อมีหลายตัว |
| Harness | ช่องทางและข้อจำกัดของการทำงาน เช่น shell, IDE หรือ agent runner ไม่ถือว่าทุก harness แชร์บริบทกัน |
| File Owner / Active Writer | ประสานสิทธิ์และการเขียนไฟล์ในช่วงเวลาที่ระบุ Ownership ในที่นี้เป็นการประสานงาน ไม่ใช่ลิขสิทธิ์ |
| Logical Resource Owner | ดูแล invariants และ compatibility ของ API, schema, format หรือ resource ร่วม |
| Reviewer | ตรวจ criteria, diff, scope และ evidence ตามระดับความเป็นอิสระที่บันทึก |
| Integrator | รับผิดชอบจุดรวมงาน ลำดับ merge การแก้ conflict และ verification หลัง integration |
| Board Coordinator / Active Board Writer | มีสิทธิ์และช่วงเวลาเขียนบอร์ดที่ชัด ตรวจ request/revision/evidence/gates และอัปเดตเฉพาะ summary จากบอร์ดล่าสุด |
| Branch / Workspace | ระบุ repository, branch/base และตำแหน่ง checkout ที่ทำงานจริง |

คนเดียวทำหลายบทบาทได้ แต่ต้องรายงาน self-review ตามจริง Critical review ต้องมีผู้ตรวจอิสระตามข้อ 8

ผู้มีอำนาจให้ scope คือมนุษย์เจ้าของโครงการ หรือ owner/integrator ที่ได้รับมอบอำนาจให้จัดสรร resource นั้นไว้แล้ว Task Owner ไม่สามารถเพิ่มสิทธิ์ให้ตัวเองเพียงเพราะพบว่าจำเป็น

## 3. Source of Truth และการตรวจสถานะจริง

| แหล่งข้อมูล | ใช้อ้างอิงอะไร |
|---|---|
| Central `TASKS.md` | Source of Truth ของ workflow status และ summary ของ owner/harness, risk, dependency/blocker, verification/review gate และ revision reference ที่จำเป็น |
| `docs/tasks/<TASK-ID>/TASK.md` | Source of Truth ของรายละเอียด Task: scope/authority, planning, criteria, actual changes, gates/evidence references และ board update requests |
| Task Directory | เก็บ evidence/handoff รายละเอียดใน `TASK.md` หรือไฟล์ย่อยตามความจำเป็น ไม่สร้างบอร์ดซ้ำ |
| Git | Repository history และเนื้อหาที่ commit แล้ว |
| Working Tree | สภาพงานที่ยังไม่ commit รวม staged, unstaged และ untracked ที่เกี่ยวข้อง |
| Verification Artifact | ผลตรวจของ revision และ environment ที่ระบุ |
| Handoff | บริบทและขั้นต่อไป ใช้ร่วมกับการตรวจสถานะจริง |
| ข้อกำหนด/ADR ที่อ้างอิง | พฤติกรรมหรือ contract ที่งานต้องรักษาหรือเปลี่ยน |

อ่านคำสั่งที่มีผลใน workspace และเอกสารต้นทางก่อนเปลี่ยนพฤติกรรม Task Board ไม่แทนการตรวจ Git status และไม่แทนสิทธิ์จากผู้ใช้หรือข้อจำกัดของ environment

หากข้อมูลขัดกัน เช่นบอร์ดบอก clean แต่มีไฟล์แก้ค้าง ให้บันทึกความต่างและ reconcile ก่อนการกระทำที่อาจกระทบงานนั้น งานอิสระที่ปลอดภัยยังทำต่อได้

### Central Board และรายละเอียดราย Task

หนึ่ง repository มี Central Task Board ที่ authoritative เพียงหนึ่งเดียวคือ root `TASKS.md` ทุก Task มี `docs/tasks/<TASK-ID>/TASK.md` และแต่ละ Task ID ในบอร์ดต้องลิงก์ถึงไฟล์นั้น บอร์ดเก็บเพียง ID/outcome/status/owner-harness/risk กับ dependencies หรือ blocker สำคัญ รวม gate summary และ Last Updated หรือ revision reference เมื่อเกี่ยวข้อง

Acceptance criteria, impact/access plan, implementation notes, detailed verification, evidence logs และ handoff อยู่ใน Task Directory ไม่คัดลอกกลับใส่บอร์ด รายละเอียด summary ในบอร์ดอ้าง source ใน directory ได้; summary ไม่เป็น grant ใหม่หรือผล verification

`TASK.md` อ้างลิงก์ไปยังบอร์ดแทนเก็บ workflow status อีกชุด การเสนอ Done หรือการมี Implementation Complete เป็นข้อมูลคนละชนิดกับสถานะที่มีผลจริง ประวัติ decision/evidence อยู่ใน Task Directory; บอร์ดเก็บสถานะล่าสุด ไม่ต้องทำ status history ซ้ำทุกที่

หากบอร์ด รายละเอียด หรือ repository/evidence ขัดกัน ให้ reconcile จาก revision จริง หลักฐานและ authority ก่อนใช้ส่วนนั้นทำงานต่อ บอร์ด authoritative ด้าน workflow ไม่ได้ทำให้ข้อมูลที่ผิดกลายเป็นข้อเท็จจริงของโค้ด/verification

### Board Writer / Status Synchronization Protocol

1. กำหนด Coordinator หรือ Active Board Writer พร้อม authority และช่วง/ขอบเขตที่เขียน ห้ามสอง agent เขียน `TASKS.md` พร้อมกันใน shared working tree ถ้าตรวจ writer ไม่ได้ให้พักการเขียนบอร์ดและเตรียม request แทน
2. Task Owner แก้ directory ของตนภายใน Authorized Scope อัปเดต evidence/gate/blocker ก่อนขอสถานะใหม่ ผู้ที่ไม่มีสิทธิ์เขียนบอร์ดใช้หัวข้อ **Board Update Request** ใน `TASK.md` หรือช่องทางที่โครงการอนุญาต ไม่ต้องสร้างระบบส่งข้อความหรือไฟล์ request ทุกครั้ง
3. Request ระบุ Task ID, requested change, expected current board state/revision, task/workspace revision และ evidence/gate references พร้อมเหตุผลตามความเสี่ยง ใช้ request reference เพื่อแยกคำขอซ้ำเมื่อจำเป็น คำขอยังไม่เปลี่ยนสถานะจริง
4. Writer อ่านบอร์ดล่าสุด ตรวจ task revision/snapshot ที่เข้าถึงได้, scope/authority, acceptance/evidence และ gates ที่เกี่ยวข้อง ไม่เชื่อ completion message เพียงอย่างเดียว บอร์ดไม่ใช้แทน verification evidence
5. หาก request stale หรือชนกับคำขออื่น ให้เทียบสถานะ/revision ใหม่ ตัดสินตาม dependency/authority และขอข้อมูลเพิ่มเฉพาะส่วนที่ขาด ไม่ replay การเปลี่ยนสถานะจาก snapshot เก่าแบบไม่ตรวจ
6. เมื่อผ่านเงื่อนไขและไม่มี writer อื่น ให้แก้เฉพาะ entry/summary ที่จำเป็นจากเนื้อหาล่าสุด รักษารายการอื่น ตรวจว่าลิงก์ถึง task/evidence ที่ถูกต้อง และคง reference ของ decision/request เมื่อจำเป็น
7. Task Owner อ่านบอร์ดหลังอัปเดตและ reconcile รายละเอียด/request ที่ค้างได้ตามสิทธิ์ Coordinator ไม่ได้มีสิทธิ์แก้ Task Directory อื่นโดยอัตโนมัติ; acknowledgement ทำโดย owner หรือผู้ที่มี scope เท่านั้น

ถ้าไม่มี Coordinator แยก Task Owner เป็น temporary Board Writer ได้เมื่อมี authority และตรวจว่าไม่มี writer อื่น การมี ownership ของ Task ไม่ให้สิทธิ์บอร์ดโดยตัวมันเอง การประสานนี้ใช้เอกสารได้ ไม่ต้องมี lock server/database

Request ที่ pending ไม่ใช่สถานะใหม่ หากหยุดหลังอัปเดต directory แต่ก่อนแก้บอร์ด ให้คงสถานะเดิมในบอร์ดและให้ผู้รับตรวจหลักฐานก่อนส่ง/ดำเนิน request ต่อ หากหยุดหลังบอร์ดอัปเดตแต่ก่อน acknowledgement ให้ยึดบอร์ดล่าสุดและ reconcile request แทนเปลี่ยนสถานะซ้ำ

## 4. Planning-first: จาก Planned ไป Ready

ก่อน Ready ให้ตอบตามขนาดงาน:

- เพิ่มความสามารถอะไร และ criteria ใดพิสูจน์ว่าทำได้
- คาดว่าจะสร้างไฟล์ใหม่อะไร และต้องแก้ไฟล์เดิมใด
- ใคร own ไฟล์หรือ logical resource เหล่านั้น และใครอนุมัติ scope ได้
- พฤติกรรมเดิม ข้อมูล และ compatibility ใดต้องคงไว้
- มี shared API, schema, configuration, build pipeline, rendering หรือ storage/undo contract ใดเกี่ยวข้อง
- ส่วนใดทำขนานได้ ส่วนใดต้องรอ interface, review หรือ integration
- ใช้ workspace ใด ใครรวมงาน และตรวจอะไรหลังรวม

Expected New Files อาจระบุเป็นรายชื่อหรือ directory/pattern ที่มีขอบเขตชัด งานที่ต้องสร้างไฟล์เพิ่มภายใน pattern ที่อนุญาตทำต่อได้และอัปเดตรายการที่สร้างจริง การเพิ่มนอก pattern หรือเปลี่ยน contract ต้องใช้ scope-change process

Ready ต้องมี scope ที่ได้รับอนุญาตและแผนสำหรับ dependency ที่ยังไม่พร้อม ไม่เริ่มส่วนที่ยังรอสิทธิ์หรือ contract โดยอาศัยการคาดเดา ใช้ planning validation ด้านล่างตรวจความพร้อมภายในขอบเขตที่จะเริ่ม

### 4.1 Planned Changes และ Actual Changes

ก่อนเริ่ม ระบุ Planned Changes ตามขนาดงาน: expected new files/bounded patterns, existing files ที่คาดว่าต้องแก้ และ preserved behavior/protected resources แผนนี้ต้องสอดคล้องกับ Authorized Access แต่รายชื่อไฟล์ที่คาดไว้ไม่จำเป็นต้องครบทุกรายการหากอนุญาต bounded pattern ชัดเจนแล้ว

ขณะทำงานให้อัปเดตสิ่งที่ทำจริงใน Task Directory เมื่อมีการเพิ่มหรือเปลี่ยนแผน และเมื่อ review/close ให้เปรียบเทียบ Actual Changes กับแผนและสิทธิ์: created, modified, deleted/moved, deviations และ logical contract changes หากมี อ้าง diff/artifact ได้โดยไม่คัดลอกไฟล์ทั้งหมดลงบอร์ด ไม่บังคับกรอก Actual Changes ล่วงหน้าก่อนมีการเปลี่ยนแปลง

ใช้ Git diff ของ staged/unstaged changes, การตรวจ untracked files และ revision context ที่ตรวจจริงตามข้อ 10 สำหรับไฟล์ที่ untracked อยู่ก่อน Task ต้องเทียบกับ pre-task snapshot ด้วย ไม่ถือว่าเป็นไฟล์ที่ Task นี้สร้างเพียงเพราะ Git แสดงเป็น untracked

แยกการต่างจากแผนกับการออกนอกสิทธิ์: helper เพิ่มเติมที่อยู่ใน authorized pattern และไม่เปลี่ยน contract เป็นการปรับแผนภายในสิทธิ์ อัปเดต Actual Changes ได้ทันที ถ้า diff นอก Authorized Scope ให้หยุดเฉพาะการแก้ที่เกี่ยวข้อง รักษางานไว้ และใช้ Scope Change Process ก่อนทำส่วนนั้นต่อหรือ integrate ห้ามย้อนอ้างว่าอนุมัติอยู่แล้ว หากเป็นงานของคนอื่นให้แยกออกจากผลส่งมอบและประสาน owner ตามข้อ 11

### 4.2 Lightweight Planning Validation

ตรวจแผนก่อนเริ่ม implementation ตามสิ่งที่เกี่ยวข้อง:

1. Outcome และ acceptance criteria ชัดและตรวจได้
2. Authorized Access ครอบคลุม Planned Changes
3. Existing owners/active writers ไม่ขัดแย้งกับการเขียนที่วางแผนไว้
4. Logical resources และ contract versions ตรงกัน
5. Dependencies พร้อม หรือระบุส่วนที่ต้องรอไว้ชัด
6. งานขนานแยกกันได้จริง รวม shared resources
7. Workspace isolation เหมาะกับความเสี่ยง
8. Integrator, target และ integration order ชัดเมื่อจำเป็น
9. Verification plan ครอบคลุม criteria และ risk

ผลตรวจแผนใช้ **Ready**, **Needs Revision** หรือ **Blocked** พร้อมผู้ตรวจและ evidence/decision reference เท่าที่จำเป็น Ready หมายถึงข้อมูลเพียงพอสำหรับเริ่มขอบเขตที่ตรวจแล้ว; Needs Revision หมายถึงต้องแก้แผน; Blocked หมายถึงติดเงื่อนไขที่ต้องปลดล็อก ระบุ blocker/unblock condition หรือส่วนที่ยังต้องรอ

ผลเหล่านี้เป็น planning metadata ไม่ใช่ Task Status ใหม่ และไม่ใช่การให้ permission เพิ่ม Trivial/Standard ใช้ self-validation ได้ High Risk/Critical ใช้ review policy และ authority เดิมตามข้อ 8 ไม่เพิ่ม mandatory planning reviewer ให้ทุก Task

Trivial รวมการตรวจนี้กับ preflight ปกติและเขียนสั้นใน TASK.md เดียวได้ ไม่ต้องตอบเก้าข้อเป็นรายงานแยก เมื่องาน/สิทธิ์/dependency เปลี่ยน ให้ตรวจซ้ำเฉพาะส่วนที่ได้รับผลกระทบก่อนเริ่มส่วนนั้น

## 5. Ownership & Access Model

> Non-owned resources may be read and extended within an authorized extension scope, but must not be modified, replaced, or deleted without explicit authorization.

สิทธิ์ระบุทั้ง resource, operation และข้อจำกัด File ownership และ logical ownership เป็นคนละมิติ ต้องผ่านทั้งสองด้านเมื่อเกี่ยวข้อง สิทธิ์จาก Task ไม่ยกเลิกข้อจำกัดของผู้ใช้ repo หรือ environment

| Permission | อนุญาต | ไม่รวม |
|---|---|---|
| Read | อ่าน วิเคราะห์ อ้างอิงภายในขอบเขตเข้าถึงได้ | การเขียน |
| Extend | Read และสร้าง resource ใหม่ในขอบเขตที่อนุญาต โดยไม่ชนของเดิม | แก้ไฟล์ที่มีอยู่ แม้เพิ่มบรรทัด, แทนที่, ลบ, ย้าย |
| Modify | Read และเพิ่ม/แก้ resource ภายในขอบเขตที่ระบุ | ลบหรือย้าย resource |
| Full | เพิ่ม แก้ ลบ หรือย้ายภายในขอบเขตที่อนุญาต | resource นอก scope และการกระทำที่ถูกห้ามโดยกติกาที่มีผล |

ไม่มี Task ใดได้ Modify ทั้ง repository โดยปริยาย การเขียนลงไฟล์เดิมต้องมี Explicit Modify; การลบหรือย้ายไฟล์ต้องมี Explicit Full การ replace resource เดิมด้วยไฟล์ใหม่ยังนับเป็นการแก้ resource เดิม

ไฟล์ใหม่มี Task ผู้สร้างเป็น owner ระหว่างงาน แต่ ownership ไม่ใช่การเพิ่ม permission อัตโนมัติ เพื่อให้เขียนไฟล์ใหม่ต่อได้โดยไม่ต้องขอทุกครั้ง scope ควรอนุญาตล่วงหน้าเป็นคู่: `Extend <expected new paths>` และ `Modify เฉพาะไฟล์เหล่านี้ที่ Task สร้างและลงทะเบียนเป็นเจ้าของแล้ว` Extend อย่างเดียวไม่อนุญาตแก้ไฟล์ที่สร้างเสร็จแล้ว การลบหรือย้ายยังต้อง Full

### Pre-write Collision Check

ก่อนสร้างไฟล์ใหม่หรือแก้ shared resources ให้ตรวจ existing path collisions, active writer/ownership conflicts, logical resource collisions, shared generated outputs และ workspace/branch context ที่อาจขัดแย้ง เช่น API name, schema field, registry key, generated output หรือ directory ที่ถูกค้นพบอัตโนมัติ ใช้ข้อมูลจาก workspace จริงและบอร์ด/owner ที่เกี่ยวข้อง การเพิ่มไฟล์ไม่ถือว่าปลอดภัยโดยอัตโนมัติ

ผลตรวจใช้ **Clear**, **Conflict** หรือ **Unknown** ระบุ resource/context ที่ตรวจและ resolution/limitations เมื่อจำเป็น:

- Clear: มีข้อมูลเพียงพอสำหรับขอบเขตที่ตรวจ ณ ตอนนั้น ไม่รับประกันว่าไม่มี conflict ทุกชนิด และไม่ใช่สิทธิ์เขียน
- Conflict: พบ path/writer/contract/output/context ที่ชนกัน ให้แก้การจัดสรรหรือ scope ก่อนเขียนส่วนที่ขัดแย้ง
- Unknown: ข้อมูลไม่พอ ห้ามตีความเป็น Clear; ถ้ากระทบ shared resource ให้ตรวจเพิ่มหรือพักเฉพาะส่วนนั้น

Planning validation อ้างผล collision check ได้ แต่ไม่แทน checkpoint ก่อนเขียน หากข้อมูลยังใช้ได้ให้อ้างผลเดิมได้ ไม่ต้องตรวจทุกครั้งที่กดบันทึก ตรวจซ้ำเมื่อมีการเพิ่ม resource, เปลี่ยน writer/contract หรือ workspace/branch context จนผลเดิมไม่ครอบคลุม Trivial รวมกับ preflight และ evidence ใน TASK.md เดียวได้โดยไม่สร้างรายงานใหม่

Logical Resource ครอบคลุมอย่างน้อย public API, database schema, file format, shared configuration, build pipeline, rendering contract และ storage/undo contract การเพิ่มไฟล์ที่เปลี่ยน semantics ของ resource เหล่านี้ต้องได้รับ scope approval หรือผ่าน integration gate ที่กำหนดไว้ก่อนให้มีผลต่อระบบ

สามารถเตรียม isolated proposal ตาม Extend scope ที่อนุญาตไว้แล้วได้ แต่ต้องไม่ register, expose หรือ activate contract ที่ยังไม่อนุมัติ การเชื่อมเข้าระบบอาจต้องให้ owner แก้ไฟล์เดิม

### Scope Flexibility ภายในสิทธิ์ที่มี

| สถานการณ์ | การดำเนินการ |
|---|---|
| Within Authorized Scope | สร้างไฟล์เพิ่มใน bounded Extend pattern ได้เมื่อ collision check ครอบคลุมและไม่ขัด ownership/contract; อัปเดต Actual Changes โดยไม่ขอ approval ซ้ำ การแก้ไฟล์ใหม่นั้นต่อใช้ conditional Modify ที่อนุญาตไว้เดิม |
| Outside Authorized Scope | การเพิ่มนอก pattern หรือแก้ existing file ที่ไม่มี Modify ต้องขอ scope change พร้อมเหตุผล impact และ alternative; รอ authority decision ก่อนแก้ส่วนนั้น งานอิสระยังเดินต่อได้ |
| Shared Contract Impact | แม้เพิ่มแค่ไฟล์ใหม่ หากเปลี่ยน API/schema/format/registry/semantics ให้ตรวจ logical owner และขอ scope approval หรือผ่าน integration gate ที่มี authority ตามที่กำหนด; ห้าม activate สิ่งที่ยังไม่ได้อนุญาต |

Extend ไม่อนุญาตให้เติมบรรทัดในไฟล์เดิม Ownership ของไฟล์ใหม่ไม่ให้ Modify อัตโนมัติ และ planning validation/collision Clear ไม่ยกระดับสิทธิ์ ใช้ additive-first ตามข้อ 6 โดยไม่สร้าง duplication/workaround เพื่อหลบ ownership

### Scope Change Request

ใช้บันทึกสั้นใน Task เดิม: เหตุผล, resource/permission ที่เพิ่ม, owner ที่เกี่ยวข้อง, impact, ทางเลือก additive, verification ที่ต้องเพิ่ม และผลตัดสินพร้อมผู้อนุมัติ/authority

เมื่อได้รับอนุมัติให้อัปเดต scope ก่อนแก้ ถ้าไม่อนุมัติหรือยังไม่ตัดสิน ให้หยุดเฉพาะส่วนที่พึ่งสิทธิ์นั้น ทำงานอิสระต่อได้ หรือส่ง patch proposal ให้ owner/integrator ลงมือแทน ห้ามตีความคำขอที่ยังรอคำตอบว่าอนุมัติแล้ว

การเปลี่ยน owner ต้องบันทึกผู้ส่ง/ผู้รับ resource, revision, งานค้าง และเวลาที่มีผล ผู้รับตรวจ workspace ก่อนเริ่มเขียน

### Task Directory Ownership

ระบุสิทธิ์แยกของ `docs/tasks/<TASK-ID>/` ใน scope: owner สร้างเอกสาร/evidence ด้วย Extend และแก้ resource ที่อนุญาตด้วย Modify รวม conditional Modify ของไฟล์ที่ตนสร้างได้ตามกฎเดิม ไม่ได้ Full ทั้ง directory อัตโนมัติ การแก้ของ Task อื่นต้องมี authorization หรือ handoff/reassignment ที่โอน resource และสิทธิ์ชัด

Task ownership และ directory ownership ไม่ให้สิทธิ์แก้ application source, central board, API/schema หรือ shared contracts การตรวจโดย reviewer ใช้ Read ได้; หากต้องเขียน review artifact ใน directory ของผู้อื่น ให้มี Extend/Modify scope เฉพาะ resource นั้น หรือให้ owner อ้างผลจาก artifact ที่ reviewer ส่งมาทางช่องทางที่อนุญาต โดยรักษาที่มาและ independence ของผล review

## 6. Additive-first Development

พิจารณาตามลำดับ:

1. ใช้ interface หรือ extension point ที่มีอยู่
2. เพิ่ม implementation ที่แยกจาก core
3. เพิ่ม adapter/integration layer
4. แก้ existing implementation เมื่อมีเหตุผลและได้รับ scope
5. Refactor ส่วนร่วมเมื่อจำเป็น พร้อม impact review

นี่เป็น preference ให้เลือกวิธีที่รักษา compatibility และดูแลต่อได้ ต้องไม่สร้าง workaround, duplication หรือ implementation คู่ขนานเพียงเพื่อหลีกเลี่ยง permission ถ้า interface เดิมไม่เหมาะ ให้เสนอการแก้ core พร้อมผลกระทบอย่างตรงไปตรงมา

ใน fork-based project กำหนด upstream core เป็น Read โดยค่าเริ่มต้น จัด owner ของ custom extensions และ integration layer แยกชัด ระบุ upstream base และ compatibility ที่ต้องรักษา รวมถึงแยกแพตช์เพื่อลด merge conflict การอนุญาตแก้ core ใน local fork ไม่ใช่สิทธิ์เขียนไปยัง upstream remote

## 7. Parallel Development และ Workspace Isolation

Branch ระบุสายประวัติ ส่วน workspace/worktree ระบุตำแหน่งไฟล์ที่กำลังทำงาน การสร้าง branch ใน checkout เดียวกันไม่ทำให้ agent แยกจากกัน

| วิธีทำงาน | ใช้เมื่อ | ข้อจำกัดที่ต้องจัดการ |
|---|---|---|
| Shared working tree + manual writer reservation | งานเล็ก ไฟล์แยกชัด และมีการประสานบอร์ด | หนึ่งไฟล์มี active writer เดียว; index, branch checkout และ build outputs ยังแชร์กัน |
| Separate worktrees หรือ isolation เทียบเท่า | งานพร้อมกันที่มีโอกาสแก้ไฟล์ชนกัน หรือต้องใช้ branch ต่างกัน | ลดการเขียนทับไฟล์ แต่ยังเกิด merge/contract conflict ได้ |

File reservation/lock ประสาน writer ภายใน shared tree ไม่ใช่ตัวแก้ conflict ข้าม branch Worktree แยกไฟล์ทำงาน แต่ยังต้องมี logical owner และ integration plan; worktree ของ repo เดียวกันยังแชร์ Git objects/refs บางส่วน

กำหนด canonical board workspace/branch กับ Board Writer ของ repository ไว้ชัด สำเนา `TASKS.md` ใน worktree อื่นเป็น snapshot สำหรับอ่าน ไม่ใช่ central board อีกชุด ห้ามแต่ละ feature branch เปลี่ยนสถานะเองแล้วค่อยถือว่าทุกสำเนาถูกต้องพร้อมกัน

Task ID ต้องไม่ซ้ำข้าม worktree ให้ประสาน allocation กับ Coordinator หรือใช้ช่วง ID ที่ได้รับมอบไว้ก่อนสร้าง directory แยก Task Directory ช่วยให้ Agent A แก้ `T001/TASK.md`, Agent B แก้ `T002/TASK.md` และ Agent C อ่าน evidence ของ T003 ได้พร้อมกัน ขณะ Coordinator เขียนบอร์ด แต่ยังต้องตรวจ application file/logical ownership และ isolation ตามเดิม

Integrator ระบุลำดับรวม task documentation, evidence references และ board summaries จากหลาย worktree ตรวจ ID/path/content collisions และรักษา uncommitted evidence ที่เกี่ยวข้อง ให้ task details/artifact references เข้าถึงได้จาก canonical context ก่อนเผยแพร่ลิงก์หรือ status update ที่อ้างข้อมูลเหล่านั้น การรวมเอกสารไม่ทำให้ code ของ Task ถูก merge หรือผ่าน verification ไปด้วย

ก่อนทำขนานให้ระบุ owner, branch/base, workspace, file scope, logical dependencies, interface version และ integrator พร้อมลำดับรวมงาน Shared resources เช่น output directory, database, device, port และบอร์ดงานต้องถูกจัดสรรด้วย ไม่สมมติว่าแยก worktree แล้วทรัพยากรทั้งหมดแยกตาม

ใน shared tree ห้าม stage/commit งานคนอื่น เปลี่ยน branch หรือทำคำสั่งที่เปลี่ยน index/ไฟล์ร่วมโดยไม่ประสาน ถ้าขอบเขต staging แยกไม่ได้ ให้รวมผ่าน integrator หรือเปลี่ยนไปใช้ isolation

Integrator ตรวจ source/target revision และสภาพงานก่อน merge หาก conflict ต้องรักษาทั้งสองเจตนาและร่วมตัดสินกับ owner ที่เกี่ยวข้อง ห้ามเลือกทิ้งอีกฝ่ายเพื่อให้ merge จบ หลัง resolve ให้ตรวจ diff และรัน verification ที่เกี่ยวข้องกับผลรวม

Task ที่ Done แต่ยังไม่ integrate ไม่ถือว่า dependency พร้อมใช้งานใน target branch จนผ่าน Integration gate ที่ dependency นั้นกำหนด

## 8. Risk, Verification และ Reviewer Independence

ใช้ความเสี่ยงและความซับซ้อนร่วมกัน งานสั้นที่ลบข้อมูลอาจเป็น Critical ได้

| ระดับ | กระบวนการขั้นต่ำ |
|---|---|
| Trivial | Task record สั้น, scope ที่ชัด, criteria, diff inspection และ evidence ของ revision ที่ตรวจ |
| Standard | Explicit access scope, acceptance criteria, verification plan และ completion evidence; review ตามผลกระทบ |
| High Risk | Ownership/contract review, failure-case verification, scope compliance review; แยก reviewer เมื่อทำได้และบันทึกข้อจำกัดถ้าไม่ได้ |
| Critical | Independent review gate, recovery/rollback plan, revision-bound evidence และ integration/release verification เมื่อเกี่ยวข้อง |

High Risk ที่ไม่มี reviewer อิสระต้องบันทึก compensating checks และ residual risk ผู้มีอำนาจที่ระบุใน Ready scope ต้องยอมรับข้อจำกัดก่อนปิด gate; ถ้าไม่ได้กำหนดผู้มีอำนาจหรือยังไม่มีการยอมรับ ให้คง In Review/Blocked ตามข้อเท็จจริง งาน Critical ปิด review gate ไม่ได้หากไม่มี independent reviewer

Reviewer ตรวจ acceptance criteria, implementation diff, scope compliance, evidence, regression risk และ data safety เมื่อเกี่ยวข้อง อ่าน artifact ต้นทางตามความจำเป็น ไม่ใช้ completion summary เป็นหลักฐานเพียงอย่างเดียว ไม่จำเป็นต้องรันทุก test ซ้ำถ้าหลักฐานตรวจสอบได้และไม่มีประเด็นค้าง

บันทึกระดับความเป็นอิสระตามจริง:

- **Self-review:** ผู้เขียนหรือ session ที่เขียนงานตรวจตัวเอง
- **Separate reviewer session/agent:** แยกจากผู้เขียน ไม่ร่วม implementation ของ revision ที่ตรวจ อ่าน diff และ evidence โดยตรง ใช้ model/provider เดียวกันได้
- **Independent human review:** มนุษย์ที่ไม่ได้เป็นผู้เขียน revision นั้นตรวจตาม scope

การใช้คนละ provider อย่างเดียวไม่พิสูจน์ความเป็นอิสระ ระบุสิ่งที่ reviewer ตรวจและข้อจำกัดด้วย ถ้า reviewer แก้ implementation ที่มีสาระ ต้องเปิดเผยว่า review ส่วนใดไม่เป็นอิสระแล้ว และให้ผู้ตรวจอีกคนตรวจส่วนที่จำเป็นตาม gate

Review ไม่ผ่าน: บันทึก finding ที่ผูก criteria/resource/revision, ส่ง Task กลับ In Progress เพื่อ rework หรือ Blocked ถ้าติดเงื่อนไข พร้อมยกเลิก gate/evidence ที่ได้รับผลกระทบ ห้ามลด criteria เพื่อให้ผ่านโดยไม่บันทึก scope/acceptance change และ authority

Scope compliance review ต้องเทียบ Planned Changes, Authorized Access และ Actual Changes จาก diff/untracked inspection ของ revision ที่ตรวจ รวม deleted/moved paths และ logical contract changes เมื่อพบ deviation ให้แยกว่าเป็นการปรับแผนภายในสิทธิ์ การเปลี่ยน scope ที่อนุมัติแล้ว หรือเรื่องนอกสิทธิ์ที่ยังต้องหยุดแก้ก่อนปิด gate อ้าง artifact เดียวกับ verification ได้โดยไม่ทำรายการไฟล์ซ้ำ

## 9. Task Lifecycle และ Delivery Gates

รักษาสถานะเดิม:

| Status | ความหมาย |
|---|---|
| Planned | อยู่ในแผน ยังไม่ผ่านความพร้อมหรือยังไม่มีผู้รับ |
| Ready | ผลกระทบ สิทธิ์ criteria และวิธีตรวจชัด ผ่าน planning validation สำหรับขอบเขตที่จะเริ่ม พร้อม claim ส่วนที่ทำได้ |
| In Progress | มี owner และเริ่ม implementation หรือ rework |
| In Review | Implementation พร้อมตรวจ ยังมี verification/review gate ค้าง |
| Blocked | ส่วนที่จำเป็นของงานไปต่อไม่ได้ ระบุ blocker และ unblock condition |
| Done | ผ่าน acceptance และ verification/review ที่กำหนดภายใน scope ของ Task |
| Deferred | อยู่นอกแผนปัจจุบัน พร้อมเหตุผลและเงื่อนไขกลับมาพิจารณา |

### เปิด Task และเปลี่ยนสถานะ

1. ตรวจ/ประสาน Task ID กับ canonical board และงานที่กำลังเตรียมใน worktree อื่น ป้องกัน ID ซ้ำ
2. ภายใต้สิทธิ์ที่ได้รับ สร้าง `docs/tasks/<TASK-ID>/TASK.md` จาก template; การเตรียม Task Directory นี้ไม่ให้สิทธิ์ implementation ของ resource อื่น
3. วาง outcome, impact/access plan, criteria และ verification plan ตรวจ planning validation และ pre-write collision ของส่วนที่จะเริ่ม
4. ลงทะเบียน ID/ลิงก์/summary ในบอร์ดผ่าน Board Writer; หากยังไม่พร้อม ใช้ Planned หรือสถานะเดิมที่เหมาะสมพร้อม blocker ไม่สมมติว่า Ready
5. เริ่ม implementation เมื่อ scope/dependencies พร้อมและการ claim เป็น In Progress ถูกสะท้อนใน central board แล้ว ใช้ขั้นตอนตามข้อ 3 โดยไม่เพิ่ม status ใหม่

เมื่อมีความคืบหน้าที่เปลี่ยน workflow status ให้อัปเดตรายละเอียด/evidence/gates/blocker ใน directory ก่อน ส่ง Board Update Request ให้ Writer ตรวจ แล้วอ่านบอร์ดยืนยันความสอดคล้อง การแก้โน้ตหรือบันทึก log ภายในสิทธิ์ไม่ต้องรอ board approval ทุกครั้ง

Blocked เกิดได้ทุกขั้น ระบุขั้นเดิมและจุดที่จะกลับไปทำต่อ งานย่อยที่ไม่พึ่ง blocker ยังทำได้; ถ้า Task หลักยังเดินต่อได้ ให้บันทึก partial blocker แทนเปลี่ยนสถานะทั้ง Task โดยไม่จำเป็น

ใช้ metadata สำหรับ gate แยกจาก Task status:

| Gate | เงื่อนไข Complete |
|---|---|
| Implementation Complete | ผลส่งมอบและ diff พร้อมตรวจตาม scope |
| Verification Complete | Required checks ผ่านหรือมีเหตุผล Not Applicable ที่ยอมรับได้ บน revision ที่ระบุ |
| Review Complete | ผ่าน review ระดับที่กำหนดและปิด finding ที่ต้องแก้ |
| Integration Complete | ผลส่งมอบอยู่ใน target ที่กำหนด ผ่าน conflict/compatibility checks ของผลรวม |
| Release Ready | Artifact ของ integrated revision ผ่าน release criteria และข้อจำกัดถูกบันทึก |

Gate ใช้ `Pending`, `Complete`, `Blocked` หรือ `Not Applicable` พร้อม revision/reference เฉพาะ gate ที่เกี่ยวข้อง Gate ที่ไม่ต้องใช้ละได้ แต่เมื่อมี delivery dependency ต้องระบุชัด Integration/Release ไม่ใช้ Not Applicable เพียงเพราะยังไม่ได้ทำ

**Done ไม่ได้แปลว่า merged หรือ released** เว้นแต่ acceptance ของ Task กำหนดให้รวมงาน/ปล่อย artifact แล้ว ถ้าเป็น branch-local task ให้ระบุ target และ Integration Pending ได้ ส่วน Task ที่ acceptance รวม integration จะ Done ไม่ได้จน gate นั้น Complete

เมื่อเปลี่ยนผลส่งมอบที่ปิดแล้ว ให้ reopen In Progress และประเมิน evidence ใหม่ ประวัติผลตรวจเดิมยังเก็บไว้แต่ห้ามใช้แทนผลของ revision ใหม่โดยอัตโนมัติ การ merge ไปอีก context อาจคง branch-local Done ไว้ได้ แต่ต้องตรวจ Integration gate ของ context ใหม่

## 10. Revision-bound Verification & Evidence

ทุกข้อสรุปว่าผ่านต้องชี้ได้ว่าตรวจอะไรบน revision ใด Evidence อยู่ใน Task Directory หรืออ้าง external artifact ที่ reviewer/ผู้รับเข้าถึงได้ตามสิทธิ์ Trivial เก็บ inline ใน `TASK.md` ได้; สร้าง `evidence/` เมื่อมี artifact ที่เหมาะแยกไฟล์ เช่น verification/diff-summary/review report บอร์ดเก็บแค่ summary และลิงก์ผ่าน Task ไม่แปะ detailed logs

Evidence record รองรับ:

- Task ID และ acceptance/check ที่ครอบคลุม
- Repository และ commit SHA หรือ working-tree snapshot identity
- Command หรือ manual procedure พร้อมเวลาเมื่อจำเป็นต่อการตรวจย้อนกลับ
- Environment เช่น OS/device, tool/dependency version และ configuration ที่มีผล
- Exit status สำหรับคำสั่ง หรือ `N/A — manual procedure` พร้อม observed result
- Verdict, result summary, failure cases และ known limitations
- Artifact reference และ reviewer/review result เมื่อจำเป็น

| Verdict | ความหมาย |
|---|---|
| Passed | ตรวจจริงและผลตรงกับสิ่งที่คาดไว้ |
| Failed | ตรวจจริงแล้วไม่ผ่าน |
| Blocked | ทำ check ไม่ได้เพราะ dependency/environment ระบุสาเหตุ |
| Not Run | ยังไม่ได้ตรวจ ห้ามนับเป็น Passed |
| Not Applicable | ไม่เกี่ยวกับ scope พร้อมเหตุผล |

Failure-case test เช่นจำลอง disk full อาจ Passed เมื่อระบบปฏิเสธ save ตามที่คาดไว้ ให้แยกผลของ test harness ออกจาก exit/error ที่จงใจสร้างภายใน scenario

### งานที่ยังไม่ commit

HEAD อย่างเดียวไม่ระบุ dirty tree ให้บันทึก base commit และ snapshot ของ input ที่ตรวจ เช่น path/content hashes ของไฟล์ใน scope พร้อม tracked diff, staged/unstaged state และรายการ untracked ที่เกี่ยวข้อง งานที่ขึ้นกับไฟล์นอก scope ต้องรวม identity ของ input เหล่านั้นด้วย หรือระบุข้อจำกัดของผลตรวจ หลีกเลี่ยงเก็บ secrets และ log ที่ไม่จำเป็น

Snapshot ควรเรียกอ้างอิงซ้ำได้ เช่น report ที่มี hash manifest และ capture procedure สำหรับงานเอกสารเล็ก ใช้ base SHA + hashes ของเอกสารที่ตรวจและคำอธิบาย scope ก็พอ ต้องไม่อ้างว่า snapshot นี้ยืนยันทั้ง repository

ก่อนตรวจ ตรวจว่างาน/input ไม่กำลังถูกแก้ระหว่างรัน และยืนยัน identity หลังตรวจ หากมีการเปลี่ยนระหว่างทาง ผลนั้นใช้ปิด gate ไม่ได้จนยืนยันได้ว่าตรวจ input ใด

### หลังมีการเปลี่ยนแปลง

ประเมิน check ที่ได้รับผลกระทบและบันทึกว่า evidence ใด stale ต้องรันใหม่เท่าที่จำเป็น ถ้าเปลี่ยนเอกสารอย่างเดียว อาจคงผลของ code tests เดิมได้เมื่อมีเหตุผลรองรับ แต่ต้องระบุ revision coverage ไม่รวมผลเก่าเป็นผลใหม่โดยอัตโนมัติ การ rebase/cherry-pick ที่ SHA เปลี่ยนต้องตรวจ equivalence และ context ก่อน carry evidence พร้อม mapping ที่อธิบายได้

Manual integration/CI report ต้องอ้าง integrated SHA หรือ artifact digest จริง มีเพียงข้อความ “tests passed” ใน completion report ไม่เพียงพอ

Task ที่ Done คง directory และ evidence/reference ไว้ให้ตรวจย้อนหลัง การ cleanup/ย้าย artifact ต้องมีสิทธิ์และ retention decision ที่รักษาหลักฐานจำเป็น ห้ามลบ directory เพียงเพราะปิด Task แล้ว External reference ต้องชี้ revision/artifact ที่ระบุได้; หากเข้าถึงไม่ได้ให้บันทึก limitation และจัดการ gate ที่พึ่ง evidence นั้น ไม่ใช้ board summary แทนหลักฐานที่หาย

## 11. Handoff, Stale Task และ Recovery

Handoff ระบุ owner/harness/workspace, branch/base/last commit, งานที่เปลี่ยนและยังไม่ commit, scope/ownership, evidence ของ revision ล่าสุด, gate ที่ค้าง, blocker/unblock condition และ exact next action อ้าง workflow status จาก central board และระบุ pending Board Update Request ที่ต้อง reconcile ผู้รับตรวจข้อมูลเทียบ workspace ก่อนเขียน

เก็บ handoff สั้นใน `TASK.md`; สร้าง `HANDOFF.md` เฉพาะเมื่อมีรายละเอียดที่ควรแยกไฟล์ ใช้เป็นบริบทส่งต่อ ไม่ใช่บอร์ดอีกชุด การโอน Task Directory ต้องมีผู้ส่ง/ผู้รับ/authority และสิทธิ์ที่โอนชัด ผู้รับไม่เขียนทับ pending changes เดิม และให้ Board Writer sync owner/status summary เมื่อการส่งมอบได้รับอนุญาตแล้ว

เพิ่ม Last Activity, Ownership Lease และ Recovery State เฉพาะเมื่อมีหลาย session หรือมีความเสี่ยงค้าง Lease คือสัญญาณประสานงาน หมดเวลาไม่ใช่สิทธิ์ลบ reset หรือยึดงานทันที

เมื่อ session หายหรือ task stale:

1. อ่าน central board, `TASK.md`, pending Board Update Request และ handoff ล่าสุด
2. ตรวจ Git status, repository และ workspace จริง
3. ตรวจ commit, staged/unstaged/untracked changes และเก็บ snapshot/reference ที่จำเป็นก่อนแตะต้อง
4. ตรวจว่าผู้เขียนเดิมยัง active หรือมีผู้รับต่อแล้วหรือไม่ ผ่านช่องทางที่อนุญาต
5. สรุปสิ่งที่กู้คืนได้และจุดที่ยังไม่แน่ใจ รวมกรณีรายละเอียดอัปเดตแล้วแต่บอร์ดยังไม่อัปเดต หรือบอร์ดเปลี่ยนแล้วแต่ request ยังไม่ acknowledge
6. บันทึก reclaim/reassignment จากผู้มีอำนาจ พร้อม scope และ writer reservation ใหม่
7. ทำต่อจากงานเดิมและตรวจหลักฐานที่ยังใช้ได้

ถ้ายืนยันไม่ได้ว่าไม่มี writer active ให้คงพื้นที่นั้นไว้ ทำงานอิสระใน workspace แยก หรือบันทึก blocker ของส่วนที่ขัดแย้ง

ห้ามทำลายงานโดยอัตโนมัติ เช่น `git reset --hard`, `git clean -fd`, force checkout ที่ทับงาน, ลบ worktree ที่มีงานค้าง หรือ force push ที่ทับประวัติ ต้องมี authorization ที่ครอบคลุมการกระทำจริง ตรวจผลกระทบ และมีแผนรักษา/กู้คืนงานก่อนดำเนินการ Permission Full ของไฟล์อย่างเดียวไม่ใช่ authorization ให้ rewrite Git history

เมื่อพบ out-of-scope changes ให้บันทึกและหยุดเฉพาะส่วนที่ขัดแย้ง ไม่ revert งานนั้นหรือ claim ว่าเป็นงานของตน และไม่จำเป็นต้องหยุดงานอิสระทั้งหมด

## 12. เอกสารและ Minimum Metadata

### โครงสร้างแนะนำหลัง Adopt

```text
repository/
├── AGENTS.md
├── TASKS.md                         # authoritative central board เดียว
└── docs/
    ├── dev-framework/DEV-FRAMEWORK-v0.2.md
    ├── templates/TASK-TEMPLATE.md    # template แยกจาก active task directories
    └── tasks/
        ├── T001/
        │   ├── TASK.md
        │   ├── evidence/            # มี artifact จึงสร้าง
        │   └── HANDOFF.md           # มีรายละเอียด handoff จึงสร้าง
        ├── T002/
        │   └── TASK.md
        └── T003/
            ├── TASK.md
            └── evidence/
```

ทุก Task มี directory ของตนตาม ID และ `TASK.md` เป็นหลัก ไม่สร้าง `TASKS.md` หรือ task board ย่อยในนั้น Done แล้วคง directory ไว้ Trivial ใช้เพียง `TASK.md` โดยไม่ต้องสร้าง `evidence/` หรือ `HANDOFF.md` เปล่า

[Task Template ฉบับปัจจุบัน](../tasks/DEV-FRAMEWORK-TASK-TEMPLATE.md) มีแบบสั้น/ขยายและตัวอย่างสำหรับนำไปสร้าง `TASK.md` โดย path ของ template ใน repo นี้ยังเป็น legacy authoring location ที่รักษาไว้ การเปลี่ยนไป `docs/templates/TASK-TEMPLATE.md` เป็น mapping ในข้อ 17 ไม่ได้ย้ายหรือสร้าง template คู่ขนานในรอบแก้ specification นี้

Ownership/integration map, ADR หรือ shared verification procedure เก็บแยกเมื่อจำเป็น แล้วลิงก์จาก Task ไม่ต้องสำเนาเนื้อหา Template และ procedure ไม่ใช่ active Task หรือ central board อีกชุด การนำโครงสร้างนี้ไปใช้ทั้ง repo ต้องแยกเป็น adoption/migration ตามข้อ 17

| ช่วง/ระดับ | Mandatory | เพิ่มเมื่อเกี่ยวข้อง |
|---|---|---|
| Planned | Board summary: ID/outcome/status/owner-harness/risk และลิงก์ไป `TASK.md` | Dependencies/blocker สำคัญ; gate summary และ Last Updated/revision เมื่อเกี่ยวข้อง |
| ทุก Task ก่อน In Progress | Owner/harness, repo/workspace/branch หรือเหตุผล N/A, risk, authorized access scope, criteria และวิธีตรวจ | Existing owner/new paths/contracts ถ้ามีผล, preservation constraints |
| Standard ขึ้นไป | Impact/dependency assessment และ verification plan ที่ explicit | Integrator/target เมื่อมีการส่งมอบข้าม branch/workspace |
| High Risk/Critical | Contract/ownership impact, failure cases, review requirement | Recovery/rollback plan บังคับสำหรับ Critical |
| ก่อน Done | Revision-bound evidence, gate ที่ต้องใช้, ข้อจำกัด/สิ่งที่ยังไม่ตรวจ และ actual-vs-plan/scope inspection | Actual file lists หรือ diff/artifact reference; independent review ตามระดับและ adoption policy |
| เมื่อ blocked/handoff/recover | Blocker/unblock condition หรือ exact next action พร้อม revision/workspace/pending changes | Last Activity, lease, reassignment authority |

ข้อมูลรายละเอียดในตารางตั้งแต่ก่อน In Progress เป็นต้นไปอยู่ใน Task Directory ไม่ต้องคัดลอกขึ้นบอร์ด Planned ที่ยังไม่มี owner/harness หรือยังไม่ประเมิน risk ระบุ Unassigned/TBD ตามจริง ใช้ `N/A + เหตุผล` เฉพาะข้อมูลที่อาจทำให้เข้าใจผิดถ้าละ รายการที่ไม่เกี่ยวกับงานละได้ ไม่ต้องทำ report แยกสำหรับ Trivial

Impact plan, collision check, planning validation และ Actual Changes เป็น conditional metadata รวมใน scope/evidence บรรทัดเดิมใน `TASK.md` ได้ ไม่ต้องเพิ่มสี่บล็อกให้ทุก Task: ก่อนเขียนตรวจสิ่งที่เกี่ยวข้อง; ตอน review/close จึงเทียบสิ่งที่เกิดจริง งาน Read-only อาจอ้างผลวิเคราะห์และบันทึกว่าไม่มี mutation ได้ ความต้องการให้ตรวจไม่บังคับให้คัดลอกข้อมูลที่อยู่ใน artifact แล้ว

### Central Board Entry และ Task Directory Examples

ตัวอย่าง Markdown ต่อไปนี้ใช้ context ของ root `TASKS.md` ใน repository สมมติ ทั้งสถานะ/ชื่อ agent เป็นข้อมูลประกอบการออกแบบ ไม่ใช่ Task หรือผลตรวจที่เกิดขึ้นจริง:

```markdown
| ID | Task | Status | Owner / Harness | Risk | Dependencies / Gates / Reference |
|---|---|---|---|---|---|
| [T001](docs/tasks/T001/TASK.md) | Canvas Input | Done | Agent A / local shell | Standard | Task-local verification/review complete; integration pending; see task evidence |
| [T002](docs/tasks/T002/TASK.md) | GPU Renderer | In Progress | Agent B / IDE | High Risk | T001 contract; integration owner I; current task revision in TASK.md |
| [T003](docs/tasks/T003/TASK.md) | Storage Adapter | Planned | Agent C / local shell | High Risk | Proposed scope/planning not yet validated |
```

แต่ละ entry เชื่อมกับ directory ชื่อเดียวกัน: T001 เก็บรายละเอียดและ evidence ที่ผ่านแล้วตามสมมติฐาน; T002 มี `TASK.md` และอาจเพิ่ม `evidence/verification.md` เมื่อมีผลจริง; T003 เก็บ proposed scope และ checks ที่ยัง Not Run คนละ owner แก้คนละ directory ส่วน Reviewer อ่าน T003 หรือเขียน review artifact เฉพาะ scope ที่ได้รับ Coordinator ดู summary และตรวจหลักฐานต้นทางก่อนแก้บอร์ด ไม่มี Task ใดต้องมี board ภายใน directory

## 13. Workflow Examples

ตัวอย่างต่อไปนี้เป็นสถานการณ์สมมติ ไม่ใช่ผลตรวจที่รันแล้ว

### A. Claim งานโดยตรวจสภาพจริง

TASK-P02 สร้าง stroke renderer ต่อจาก input contract P1 ผู้รับอ่าน board/docs และพบ shared tree มีไฟล์ input ของคนอื่นแก้ค้าง จึงเลือก worktree ของตนที่ base B0 เตรียม `docs/tasks/TASK-P02/TASK.md` พร้อม Read ของ input contract, Extend/conditional Modify ของไฟล์ renderer ใหม่ และให้ integrator แก้ registration เมื่อพร้อม ตรวจ planning ทั้ง dependency/contract/isolation และ pre-write collision แล้วส่งการลงทะเบียน/claim ให้ Board Writer อัปเดตเป็น In Progress เมื่อพร้อม ผู้รับอ่านบอร์ดยืนยันก่อนเริ่ม implementation ไม่เปลี่ยนสถานะเองเฉพาะใน Task Directory ตัวอย่างนี้ไม่ได้อ้างว่าตรวจผ่านแล้วจริง

### B. Extend ส่วนที่ไม่ได้ own

TASK-P04 วางแผนเพิ่ม `extensions/export_pdf_adapter.rs` และมี authorized Extend pattern `extensions/export_pdf_adapter*.rs` พร้อม conditional Modify ของไฟล์ที่ Task สร้าง หากภายหลังต้องเพิ่ม `extensions/export_pdf_adapter_checks.rs` ให้ตรวจ collision/contract ของ path ใหม่แล้วเพิ่มได้ทันทีเมื่อ Clear และอยู่ในสิทธิ์ อัปเดต Actual Changes ผ่าน diff/snapshot โดยไม่ขอ approval ซ้ำ แม้ชื่อ helper ไม่อยู่ในรายการคาดไว้ตอนแรก หากต้องเติมบรรทัดใน `extensions/registry.*` ให้ขอ Modify หรือส่ง integration patch ให้ owner; Extend ไม่ครอบคลุมบรรทัดนั้น

### C. Scope escalation และ additive trade-off

พบ interface เดิมส่ง pressure ไม่ครบ ผู้เขียนอธิบายว่า adapter อย่างเดียวจะทำให้ข้อมูลหาย เสนอแก้ `input_contract.*` และ event semantics พร้อม compatibility checks ให้ logical owner/integrator ผู้มี authority อนุมัติ บันทึก authority decision และ scope ใหม่ใน `TASK.md` ก่อนแก้ แจ้ง Board Writer ถ้าต้องเปลี่ยน status/dependency/summary ระหว่างรอทำ renderer ที่ไม่พึ่ง field ใหม่ต่อได้ ไม่สร้าง interface สำเนาเพื่อหลบ permission

### D. Parallel work และ logical conflict

Agent A ทำ renderer และ Agent B ทำ storage ใน worktree คนละชุด แต่ทั้งคู่วางแผนใช้ stroke ID format คนละเวอร์ชัน Collision check ให้ Conflict ด้าน logical resource แม้ path ไม่ชนกัน Planning validation ให้ Needs Revision หากปรับแผนตาม contract เดิมได้ หรือ Blocked หากต้องรอ owner ตัดสิน contract ใหม่ ให้ owner ของ format กำหนด S1 และ dependency ของทั้งสองก่อนเริ่มส่วนที่กระทบ แล้ว Integrator รวม contract → renderer → storage และตรวจ interaction ของผลรวม การไม่มี file conflict ไม่ทำให้ contract gate ผ่านเอง

### E. Review, rework และ evidence stale

Revision R1 ผ่าน stroke tests แต่ reviewer พบ Undo ล้างผิด layer ตาม criterion AC-3 Task กลับ In Progress ผู้เขียนแก้เป็น R2 ต้องระบุผล R1 ที่หมด coverage แล้วรัน Undo/regression checks ที่เกี่ยวข้องใหม่ Reviewer อ่าน diff R1→R2 และหลักฐานของ R2 ก่อนปิด Review gate การเปลี่ยนชื่อ SHA ในรายงาน R1 ไม่ใช่ verification ของ R2

### F. Recovery และ merge conflict

Agent A หายกลาง Task พร้อม dirty tree B0+Δ และ request In Review ที่ยังไม่ถูกใช้ ผู้รับอ่าน central board ที่ยัง In Progress พร้อม `TASK.md`/handoff บันทึก snapshot ตรวจว่าไม่มี writer คนอื่น แล้วรับ directory/source scope ผ่าน reassignment ที่บันทึกไว้ รักษา Δ และตรวจ evidence ก่อนส่ง request ต่อ Board Writer ไม่อนุมานว่า In Review แล้วจากคำขอ เมื่อ Integrator merge กับงาน B แล้ว conflict ใน registry ให้รักษาทั้งสอง registration เจรจาพฤติกรรมกับ owners และตรวจ integrated revision I1 รวมทั้งรักษา task directories ของทั้งสองฝ่าย

### G. งานเล็ก

TASK-D01 แก้ลิงก์เอกสารหนึ่งจุด: `docs/tasks/TASK-D01/TASK.md` ไฟล์เดียวระบุ Modify ไฟล์นั้น, owner, base/workspace, criterion ว่าลิงก์ถึงเป้าหมายจริง รวม planning/collision ไว้ใน preflight และอ้าง diff/link inspection + final file hash เป็น Actual Changes/evidence จบด้วย self-review ที่ระบุไว้ได้ งาน Read/Extend/Full ใช้รูปแบบเดียวตามสิทธิ์ Full ไม่ลด risk/review ผู้รับอาจเป็น temporary Board Writer หากได้รับสิทธิ์และไม่มี writer อื่น จึงอัปเดต central summary โดยไม่ต้องสร้าง evidence/HANDOFF หลายไฟล์

### H. Concurrent Board Requests

Agent A และ B แก้คนละ Task Directory และขอ Done กับ In Review พร้อมกัน Coordinator เป็น writer เดียว อ่าน request/revision/evidence ของแต่ละงานจาก context ที่เข้าถึงได้ อัปเดตทีละ entry จากบอร์ดล่าสุด ถ้า reviewer พบ AC ของ A ไม่ผ่าน ให้คง/เปลี่ยน A เป็น In Progress ตามการตัดสินที่มีหลักฐาน ไม่รับคำขอ Done ส่วน B ทำต่อได้ หาก Coordinator หยุดหลังอัปเดต B ให้ผู้รับดูบอร์ดจริงก่อนดำเนิน request B ซ้ำ ไม่ overwrite entry A จาก snapshot เก่า

## 14. Framework Pilot และ Acceptance Criteria

ใช้เส้นทางเดิม: **Pen Input → Stroke Rendering → Save → Reopen → Verify Persistence** แบ่งเป็น contract/input, rendering, persistence, integration/verification ตาม dependency ที่ตกลง ไม่ถือว่าการทำเอกสารนี้อนุญาตให้แก้ application source เพื่อรัน pilot

ทุก scenario ต้องมีสภาพตั้งต้น เหตุการณ์ การตัดสินใจตาม framework และหลักฐานที่ผู้ตรวจตามอ่านได้ รอบแรกใช้ tabletop simulation และบัตรตัวอย่างได้; ต้องระบุว่า simulated การตรวจ runtime ของแอปต้องทำเมื่อได้รับ authorization แยกแล้ว

| # | สถานการณ์ | Expected Framework Behavior | เกณฑ์ตรวจรับ |
|---|---|---|---|
| 1 | สอง agent ทำขนาน คนละ application scope และ Task Directory | Planning validation ตรวจ scope/dependency/isolation; A แก้ T001/TASK.md, B แก้ T002/TASK.md; Coordinator เขียนบอร์ด | Task docs ต่าง directory ไม่ทับกัน ไม่มี agent เขียนบอร์ดหรือ directory คนอื่นโดยไม่มีสิทธิ์; application/logical checks และ integration plan ยังคงอยู่ |
| 2 | เพิ่มไฟล์ในส่วนที่ไม่ได้ own รวมไฟล์เพิ่มใน approved pattern | ตรวจ Extend scope, collision และ logical impact; ภายใน pattern ทำต่อและอัปเดต Actual โดยไม่ขอซ้ำ ถ้าไม่มีสิทธิ์ให้ขอก่อน | ไฟล์เพิ่มที่ไม่คาดไว้แต่ยังอยู่ใน pattern สร้างได้โดยไม่มี repeat approval เมื่อ check Clear; แก้ต่อเฉพาะที่มี conditional Modify; registration รอ gate ที่กำหนด |
| 3 | ขอแก้ existing file ของ owner อื่น หรือพบ planned/actual diff ไม่ตรง | แยกการต่างจากแผนภายในสิทธิ์กับนอกสิทธิ์; อ้าง diff/untracked snapshot; ส่วนที่ต้องเพิ่มสิทธิ์ใช้ scope request หรือ owner ลงมือแทน | Actual/deviation ตรวจย้อนกลับได้; ไม่มีการแก้นอกสิทธิ์ต่อหรือ integration จน authority ตัดสิน; การพบงานค้างไม่เป็นเหตุให้ทิ้งหรือ reset งาน |
| 4 | Agent หยุดพร้อม uncommitted changes ก่อน Board Update | อ่าน central status และ pending request พร้อม task evidence, เก็บ snapshot และตรวจ writer/reassignment; request ไม่เป็นสถานะจริง | Δ เดิมยังอยู่และแยกได้จากงานใหม่ ไม่มี reset/clean/overwrite; Board Writer ตรวจ revision/gates ก่อนใช้ request และไม่ใช้ซ้ำหากบอร์ดอัปเดตไปแล้ว |
| 5 | Logical conflict แต่ไม่ชนไฟล์ ตรวจพบก่อน implementation | Collision เป็น Conflict; planning เป็น Needs Revision หรือ Blocked ตามทางแก้; owner ตัดสิน contract/version ก่อนเริ่มส่วนที่กระทบ | มี planning finding และ revision ของแผนหลังแก้หรือ unblock condition; ไม่มี shared-contract write/activation ขณะยังไม่อนุญาต |
| 6 | Done task ตรวจย้อนหลังได้ และมี code change หลังผลเดิม | คง directory/evidence ของ R1 ไว้ ประเมิน stale checks ของ R2 และใช้ Board Update เมื่อ reopen | ตามจาก board link ถึงหลักฐาน R1 ที่ตรวจได้; R2 มีผลใหม่หรือ coverage rationale; Not Run ไม่ถูกนับผ่าน และไม่เขียนทับประวัติ evidence ให้ดูเหมือนตรวจ R2 |
| 7 | สอง Task ขอเปลี่ยนสถานะพร้อมกัน และ reviewer พบ AC ไม่ผ่าน | Writer เดียวตรวจแต่ละ request/criteria/revision; serialize minimal updates จาก latest board; งานไม่ผ่านกลับ rework/Blocked | ไม่ lost-update entry อื่นและไม่รับ Done ที่ evidence ไม่ครบ; request/board edit ไม่ถูกนับเป็น verification; งานที่ไม่ติด blocker ยังทำต่อได้ |
| 8 | Merge application conflict และ Task Directories จากหลาย worktrees | Integrator ประสาน owners รักษาเจตนา/เอกสาร/evidence ตรวจ ID/path/link conflicts และผลรวม; canonical Board Writer ดูแล status | ทั้งสอง Task Directory/evidence อยู่ครบและเข้าถึงได้ก่อนอ้างบนบอร์ด; มี diff/verification ของผลรวม; ไม่รวมสำเนาบอร์ดให้เป็นหลาย authority หรือทิ้งงานอีกฝ่าย |
| 9 | โอน Task Directory ให้ agent ใหม่ | บันทึกผู้ส่ง/ผู้รับ/authority/resource permissions; ผู้รับอ่าน TASK.md/handoff/evidence/pending requests และตรวจ Git/workspace ก่อนทำต่อ | ผู้รับรู้ scope/pending changes/valid evidence/exact next action; Board Writer sync owner summary; ไม่ถือว่า ownership อนุญาตเขียนทับงานเดิมหรือแก้ source อัตโนมัติ |
| 10 | งานเล็ก | เลือก Read/Extend/Modify/Full ตาม resource/operation/authority; รวม preflight/actual/evidence ใน TASK.md เดียวและใช้ authorized board update | มี directory และ TASK.md ของตนพร้อม board link; ไม่บังคับ evidence/HANDOFF แยกหรือ Modify ไฟล์เดิม; summary บอร์ดไม่สำเนารายละเอียดทั้งหมด |

**Pilot ผ่านด้าน workflow เมื่อ:** ทั้งสิบ scenario มีบันทึกที่ตรง expected behavior; ไม่พบ unauthorized mutation/การสูญงาน; ทุกคำอ้าง Passed/Done มี evidence ตรง revision; dependency และ delivery gate ไม่ถูกตีความผิด; ผู้รับใหม่ทำต่อได้จาก handoff; Trivial ใช้ TASK.md เดียวได้ รวมทั้งพิสูจน์ว่าเพิ่มไฟล์ภายใน pattern ไม่ต้องขอซ้ำ, planned/actual deviations ถูกจัดการ, logical conflict พบก่อนเขียน และ Planning Ready ไม่แทน permission เมื่อ collision ข้อมูลไม่พอให้ Unknown และพัก/ตรวจเพิ่มเฉพาะส่วนร่วม ต้องมี board authoritative เดียว ลิงก์ครบ ไม่มี status mirror/request ที่ถูกตีความเป็นสถานะจริง และทั้งหกสถานการณ์ board/directory ข้างต้นตรวจได้

**Pilot ผ่านด้าน application เมื่อได้รับอนุญาตให้ทดลอง:** กำหนด input sample, expected stroke representation, rendering tolerance และ persistence comparison ก่อนรัน จากนั้นตรวจ input→render→save→reopen ว่าข้อมูลกลับมาตาม criterion พร้อม failure case ของ save ที่ตกลงไว้ และ evidence ของ integrated revision/environment จริง ต้องรายงานผล application แยกจาก workflow simulation

หากไม่มี environment ให้บันทึก runtime เป็น Blocked/Not Run ห้ามใช้ tabletop pass เป็นหลักฐานว่าแอปทำงานแล้ว หาก pilot พบช่องว่าง ให้เปิด finding ปรับ framework แล้วตรวจ scenario ที่ได้รับผลกระทบซ้ำ

**สถานะในเอกสารฉบับนี้:** กำหนด procedure/criteria แล้ว; framework pilot ทั้งสิบสถานการณ์และ application pilot ยัง **Not Run**

## 15. Change Summary และความต่อเนื่องจาก v0.1

| v0.1 | v0.2 | เหตุผล |
|---|---|---|
| เป้าหมายและหลักการเดิม | คงไว้ในข้อ 1–3 | ใช้ได้ทั้งมนุษย์และ agent โดยไม่ผูก stack/provider |
| Initiative → Epic → Task → Subtask | คงไว้ในข้อ 2 รวมงานทดลอง | รักษาการแบ่งงานตามผลส่งมอบ |
| ระบุ write scope ก่อนเริ่ม | เพิ่ม impact planning และ Read/Extend/Modify/Full ในข้อ 4–5 | File list อย่างเดียวไม่ครอบคลุม contract หรือ authority |
| หนึ่งไฟล์หนึ่งผู้เขียน | แยก writer reservation, workspace isolation และ logical ownership ในข้อ 7 | แก้ปัญหาหลาย harness/worktree และ conflict ข้าม branch |
| เลือกวิธี implementation | เพิ่ม additive-first ที่มีข้อยกเว้นเชิงเทคนิคในข้อ 6 | ลด core churn โดยไม่ส่งเสริม duplication |
| Lifecycle เจ็ดสถานะ | คงสถานะ เพิ่ม gates ในข้อ 9 | แยก task-local completion จาก merge/release |
| Verification ตามความเสี่ยง | เพิ่ม progressive formality และ reviewer independence ในข้อ 8 | งานเล็กคล่องตัว งานเสี่ยงมี gate ชัด |
| Evidence และ Done criteria | ผูก revision/environment และแยกห้า verdict ในข้อ 10 | ป้องกันผลเก่าหรือสิ่งที่ยังไม่ตรวจถูกนับว่าผ่าน |
| Handoff | เพิ่ม stale-task, lease และ recovery ในข้อ 11 | รักษางานข้าม session และ harness |
| โครงเอกสาร | บอร์ดกลางเก็บ summary; แต่ละ Task มี TASK.md ของตน เพิ่ม evidence/handoff เฉพาะเมื่อจำเป็นในข้อ 12 | ลดการเขียนไฟล์ร่วมและข้อมูลซ้ำ โดยคงกระบวนการเบาสำหรับ Trivial |
| End-to-end pilot เดิม | คงเส้นทางเดิม เพิ่มสิบ coordination scenarios ในข้อ 14 | ตรวจ framework ทั้งทางปกติและเหตุขัดข้อง |

ไม่ได้ทิ้งหลักการ v0.1 การเปลี่ยนที่มีสาระคือทำขอบเขต/authority ชัดขึ้น และแยก Task Done จาก delivery state ที่ v0.1 ยังไม่ได้กำหนดละเอียด

### Scope Planning & Validation refinement ภายใน v0.2

- เพิ่ม Planned/Actual Changes และ review จาก diff/untracked snapshot เพื่อเทียบแผน สิทธิ์ และสิ่งที่เกิดจริงโดยไม่คัดลอกรายการไฟล์ซ้ำ
- เพิ่ม pre-write collision verdict Clear/Conflict/Unknown และ planning verdict Ready/Needs Revision/Blocked เป็น metadata; คงเจ็ด Task Status เดิม
- ทำเส้นแบ่ง within-scope / outside-scope / shared-contract ชัดขึ้น อนุญาต bounded extension ที่ได้รับสิทธิ์แล้วโดยไม่ขอซ้ำ
- แม่แบบ Trivial เลือก access ได้ทุกระดับ รวม checks ในบัตรเดียว; Standard+ มี conditional fields และตัวอย่าง TASK-P03 ที่ยังไม่อ้างผลจริง
- ปรับ examples และ pilot เดิมสิบข้อให้ครอบคลุม planning conflict และ plan-vs-actual deviations โดยคง revision evidence และ progressive formality

### Consistency Review Findings ของรอบ refinement

การตรวจนี้เป็น **self-review ของ refinement รอบ Scope Planning** เทียบ specification, template, workflow examples และ pilot criteria ไม่ใช่ independent review หรือการรัน pilot ผลของ TASK-58 อ้าง revision/hashes ของรอบนั้นเท่านั้น ไม่ได้ยืนยันเนื้อหาที่เปลี่ยนในรอบ Central Board; หลักฐานรอบปัจจุบันอ้าง TASK-59 ด้านล่าง

| ประเด็นที่ตรวจ | Finding / ข้อสรุปหลังปรับ |
|---|---|
| Extend และ existing files | แม่แบบ Trivial เดิมเริ่มที่ Modify ซึ่งแคบเกินงาน Read/สร้างไฟล์ใหม่ แก้เป็นตัวเลือกสิทธิ์ตาม operation; Extend ยังห้ามแก้ไฟล์เดิม รวม registry |
| Conditional Modify | คงการให้สิทธิ์ล่วงหน้าเฉพาะ task-created files ใน scope; ตัวอย่าง pattern ไม่ครอบคลุมไฟล์ที่มีอยู่ก่อนหรือคนอื่นสร้าง |
| Ownership กับ permission | ยังคงแยกกันในข้อ 5 และทั้งสองแม่แบบ ไม่ได้ให้สิทธิ์เพิ่มเพราะเป็นผู้สร้าง/owner |
| Planning validation กับ authority | เพิ่มผลตรวจแผนแยกจาก Task Status และ grant; TASK-P03 เดิม Ready ทั้งที่ preflight ยังไม่ตรวจ ปรับเป็น Planned และใช้ Pending/Not Run สำหรับขั้นที่ยังไม่เกิด |
| Done กับ integration/release | คงความหมายในข้อ 9 และตัวอย่าง branch-local Task; metadata ใหม่ไม่ทำให้ gate ส่งมอบผ่านอัตโนมัติ |
| Revision-bound evidence | Actual inspection อ้าง revision/diff/untracked snapshot ตามข้อ 10; แยก pre-existing untracked ออกจากไฟล์ที่ Task สร้างใหม่และคงกฎ stale evidence |
| Trivial จบในบัตรเดียว | รวม planning/collision ใน preflight และ Actual ใน evidence; ไม่บังคับกรอก file lists หรือรายงานแยก |
| Progressive formality | Self-validation ใช้ได้ใน Trivial/Standard; High Risk/Critical คง review policy เดิม ไม่มี planning reviewer บังคับใหม่ และ pilot ยังสิบ scenario |

**ข้อจำกัด/สิ่งที่ยังต้องกำหนดเมื่อ adopt:** โครงการต้องเลือก bounded patterns, authority references และ shared-resource owners ที่ตรวจได้จริง; manual collision check ยังไม่ใช่ atomic lock และมีผลตาม context ที่ตรวจเท่านั้น Independent review, tabletop pilot ทั้งสิบข้อ และ application pilot ยัง **Not Run** การส่งมอบรอบนี้ไม่ตัดสินแทนขั้นตอนเหล่านั้น

### Central Board & Per-Task Directory refinement ภายใน v0.2

- กำหนด `TASKS.md` เป็น workflow authority เพียงหนึ่งเดียว รายละเอียดและหลักฐานอยู่ใน `docs/tasks/<TASK-ID>/TASK.md` กับไฟล์ย่อยที่จำเป็น
- เพิ่ม directory ownership และ Board Writer protocol พร้อม request/revision/gate checks; pending request ไม่เปลี่ยนสถานะจริง และ board edit ไม่ใช่ verification evidence
- ปรับ template ให้ใช้เป็น TASK.md โดยอ้างสถานะจากบอร์ด เพิ่มตัวอย่าง entry/directory และการ sync หลัง interruption/reassignment/worktree integration
- คงเจ็ดสถานะ สิทธิ์สี่ระดับ planning/actual model, independence ตาม risk และ revision-bound evidence; Trivial ใช้ TASK.md เดียวได้
- เพิ่ม migration mapping ที่รักษาข้อมูล/ลิงก์เก่า แยกจาก adoption และขยาย pilot เดิมสิบข้อให้ครอบคลุมหกเหตุการณ์ coordination ใหม่

Consistency review รอบนี้ต้องตรวจสี่แนวเดียวกันใน specification/template/examples/pilot: board มีเพียง summary และ authority เดียว; Task Directory มี details/evidence แต่ไม่มี status mirror/board ย่อย; ทุก writer/reviewer/handoff มี scope จริง; directory isolation ไม่แทน application/contract isolation ผล self-review และ content identity ของเอกสารที่ส่งมอบบันทึกใน [TASK-59](../tasks/TASK-59/TASK.md) ไม่ใช่ผล pilot

## 16. Trade-offs และสิ่งที่ยังไม่ทำเป็น Automation

- **เอกสารก่อนระบบประสานงาน:** เริ่มใช้ได้ทุก harness แต่พึ่งวินัยการอัปเดตบอร์ดและไม่รับประกัน lock แบบ atomic เมื่อหลาย agent แข่งกัน ต้องประสานผู้เขียนหรือแยก workspace; ถ้าตรวจ writer ไม่ได้ให้รักษาพื้นที่นั้นไว้
- **Scope ชัดกับความคล่องตัว:** Permission ทำให้เกิดคำขอเพิ่มเมื่อวางแผนไม่ครบ จึงอนุญาต bounded extension และ conditional Modify ของไฟล์ใหม่ไว้ล่วงหน้าได้ แต่ไม่ให้ขยายถึง shared contract เงียบ ๆ
- **Additive-first กับความซับซ้อน:** ลด conflict ได้บางส่วน แต่ adapter มากเกินไปเพิ่มหนี้ จึงต้องยอมแก้ core เมื่อเหตุผลดีกว่าและมี ownership/impact review
- **Worktree กับ integration:** ลดการทับ dirty files แต่ไม่ลดความจำเป็นต้องตกลง semantics และตรวจผลรวม ใช้ isolation เมื่อคุ้มกับค่า setup/build
- **Independent review กับทรัพยากร:** เพิ่มความเชื่อมั่นแต่ใช้เวลา Critical ยอมรอ gate; High Risk ระบุข้อจำกัดและให้ผู้มี authority รับ residual risk ตามกติกา
- **Revision identity กับค่าเก็บหลักฐาน:** งานเล็กใช้ hashes สั้น งานใหญ่เก็บ report ตามความจำเป็น ไม่ duplicate log หรือแนบข้อมูลลับ
- **บอร์ดกลางกับความขนาน:** แยก Task Directory ลด documentation conflict แต่บอร์ดยังเป็นจุดรวมที่ต้อง serialize ผู้เขียน การรอ sync ชั่วคราวจึงต้องแยกจากความคืบหน้า/gate ใน directory และใช้ request ที่ตรวจย้อนกลับได้
- **หนึ่ง directory ต่อ Task กับความเรียบง่าย:** มี path เพิ่มแม้เป็น Trivial แต่ใช้ TASK.md ไฟล์เดียวและ inline evidence ได้ ไม่บังคับโฟลเดอร์ evidence/handoff เปล่าหรือ status history ซ้ำ

ยังไม่สร้าง orchestrator, database, lock server, dashboard, agent scheduler, automatic lease takeover หรือระบบ merge/recovery อัตโนมัติ ยังไม่ให้ระบบตัดสินว่า Done จาก completion message หรือยก scope เอง

เริ่มพิจารณา automation เมื่อ pilot/งานจริงแสดงปัญหาซ้ำที่วัดได้ เช่นลิงก์ evidence หาย field สำคัญตก หรือ snapshot ไม่ตรง revision จึงเพิ่ม checker ที่มีขอบเขตเล็กก่อน การตัดสิน logical conflict, ความหมายของ criteria และ destructive recovery ยังต้องมีผู้รับผิดชอบที่ระบุ authority ชัด

## 17. การนำไปใช้

ผู้ดูแลโครงการเลือกรับ v0.2 และกำหนด authority/critical gates ของโครงการก่อนใช้เป็นข้อบังคับ จากนั้นอัปเดตคำสั่งและแม่แบบเท่าที่จำเป็นโดยเปิด Task ที่มี scope ชัด เริ่มกับ pilot ขนาดเล็ก เก็บ friction/finding แล้วปรับเฉพาะส่วนที่มีหลักฐานว่าช่วยงานได้

### Migration Mapping และ Compatibility

ตรวจโครงสร้างเดิมและจัดทำ mapping ก่อนย้าย โดยรักษา ID เดิม ไม่จำเป็นต้องเปลี่ยน `TASK-57` เป็น `T001` เพียงเพราะชื่อในตัวอย่างต่างกัน:

| โครงสร้างเดิม | Target หลัง migration ที่อนุญาต | สิ่งที่ต้องรักษา |
|---|---|---|
| Detailed cards ใน root TASKS.md | Summary ที่ลิงก์ `docs/tasks/<existing-ID>/TASK.md`; รายละเอียดอยู่ใน Task Directory | IDs/statuses/owners/risk/dependencies/criteria/evidence/authority เดิม; snapshot/reference ของข้อมูลเก่าที่ตรวจได้ |
| Task documents ที่มีอยู่แล้ว | ใช้หรือ map ไป directory ตาม ID เดิม | ตรวจเจ้าของและผู้ใช้งานก่อน; preserve content/history/relative references; ห้ามลบหรือย้ายเอง |
| Template path เดิม เช่น `docs/tasks/DEV-FRAMEWORK-TASK-TEMPLATE.md` | `docs/templates/TASK-TEMPLATE.md` | แยกจาก active tasks; เมื่อได้รับอนุญาตจึงย้าย/ปรับ references หรือคง compatibility link ที่ชัด ไม่มี template สองชุดที่ต่างเป็น source หลัก |
| Shared verification docs / external artifacts | ลิงก์จาก TASK.md หรือ evidence report ของ Task | คง procedure/artifact revision และ access; ไม่ย้ายทุกไฟล์หรือคัดลอก log โดยไม่จำเป็น |
| Handoff ในบอร์ด/เอกสารเดิม | TASK.md หรือ HANDOFF.md ตามขนาด | Current workspace/branch/pending changes/ownership/valid evidence/exact next action |

Adoption/migration ต้องมี Task และ scope/authority ของตน ผู้มีอำนาจกำหนด canonical board context, Board Writer และ ID allocation ก่อนตัดระบบ ตรวจรายการ IDs/count/status/owners/evidence references ก่อน-หลัง และ archive ข้อมูลเดิมเมื่อจำเป็นโดยระบุว่าเป็น historical record ไม่ใช่บอร์ด workflow อีกชุด

สร้าง/รวม Task Documents ให้พร้อมก่อนเปลี่ยน board links แล้วอัปเดต relative links ตามตำแหน่งจริง ทดสอบว่าไม่มี Task หาย ไม่มี ID ซ้ำ ทุก entry ไปยัง TASK.md ที่ถูก และหลักฐานเก่าตามได้ Coordinator serialize การตัดระบบ ห้ามลบ/ย้ายเอกสารที่มีผู้ใช้งานหรือแก้ policy นอก scope; หากยังอนุญาตไม่ครบให้เสนอ mapping และคงโครงสร้างเดิมไว้ก่อน

**ขอบเขตของ revision นี้ใน repo ปัจจุบัน:** แก้ specification/template และเก็บรายละเอียดงานเอกสาร TASK-59 ใน directory ของตนเท่านั้น ยังไม่ migrate legacy cards/template path และไม่แก้ root AGENTS.md หรือ active repository policy โครงสร้างแนะนำเป็นข้อกำหนดสำหรับการ adopt ต่อไป ไม่ใช่คำอ้างว่าทุก Task เก่าได้ directory แล้ว

การส่งมอบ specification นี้ยืนยันเพียงความครบของร่างเอกสาร ไม่ได้ยืนยันว่า framework ผ่าน pilot, ถูก adopt แล้ว หรือ application ผ่าน runtime verification
