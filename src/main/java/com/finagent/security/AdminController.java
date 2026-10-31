package com.finagent.security;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 用户 / 角色 / 资源授权管理（阶段 18 ST18 需求 11）。
 *
 * <p>三条边界，写在最前面免得以后被"顺手"放宽：
 * <ol>
 *   <li><b>只有 ADMIN 能进来</b>：{@link #requireAdmin()} 在每个端点第一行，403 而不是 401 ——
 *       401 会让前端以为该重新登录，实际是这个人不该有这个页面；</li>
 *   <li><b>只能动本租户</b>：所有 SQL 带 {@code tenant_id = 当前租户}，管理员不是跨租户超管；</li>
 *   <li><b>不能删用户、不能改密码</b>：删除会破坏审计（会话、文档 owner、授权都挂着外键）；
 *       改密码应该是用户自己的动作（找回/改密），不是管理员替他设一个新密码后告诉他。</li>
 * </ol>
 *
 * <p>所有列表都是 <b>服务端分页</b>（{@code page} 从 1 开始，{@code size} 上限 100）：
 * 上市公司内部几千人，前端一次拉全量既慢又容易把表格拖死。
 *
 * <p>角色 {@code is_system} 不做保护：本版允许把 ADMIN 授给新用户 —— 演示里要建一个只读账号
 * 和一个管理员账号，禁掉反而挡住了主要用途。真要收紧，加一条 {@code if (role.system && !current.isAdmin())}。
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9_.-]{3,32}$");
    /** 角色 code：大写字母开头，之后允许数字与下划线，最长 32（与列宽一致）。 */
    private static final Pattern ROLE_CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,31}$");
    private static final int MIN_PASSWORD = 8;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");
    private static final Set<String> SUBJECT_TYPES = Set.of("ROLE", "USER");
    private static final Set<String> RESOURCE_TYPES = Set.of("DOCUMENT", "COMPANY");

    private final AdminRepository admin;

    public AdminController(AdminRepository admin) {
        this.admin = admin;
    }

    // ---------- 概览 ----------

    @GetMapping("/overview")
    public ResponseEntity<?> overview() {
        CurrentUser me = requireAdmin();
        return ResponseEntity.ok(admin.overview(me.tenantId()));
    }

    // ---------- 用户 ----------

    @GetMapping("/users")
    public ResponseEntity<?> users(@RequestParam(required = false) String q,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        CurrentUser me = requireAdmin();
        return ResponseEntity.ok(admin.searchUsers(me.tenantId(), q, status, pageOf(page), sizeOf(size)));
    }

    /** 用户能读哪些文档、为什么能读（管理页的「文档权限」抽屉）。 */
    @GetMapping("/users/{id}/documents")
    public ResponseEntity<?> userDocuments(@PathVariable long id,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        CurrentUser me = requireAdmin();
        if (!admin.userExists(me.tenantId(), id)) {
            return notFound("用户不存在 id=" + id);
        }
        return ResponseEntity.ok(admin.documentsOfUser(me.tenantId(), id, pageOf(page), sizeOf(size)));
    }

    record CreateUser(String username, String displayName, String password, List<String> roles) {
    }

    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody(required = false) CreateUser body) {
        CurrentUser me = requireAdmin();
        if (body == null) {
            return bad("请求体不能为空");
        }
        String username = body.username() == null ? "" : body.username().trim();
        if (!USERNAME.matcher(username).matches()) {
            return bad("用户名只能用字母、数字、下划线、点、短横线，长度 3~32");
        }
        if (body.password() == null || body.password().length() < MIN_PASSWORD) {
            return bad("初始密码至少 " + MIN_PASSWORD + " 位");
        }
        List<String> roles = body.roles() == null || body.roles().isEmpty() ? List.of("VIEWER") : body.roles();
        List<String> unknown = admin.unknownRoles(me.tenantId(), roles);
        if (!unknown.isEmpty()) {
            return bad("角色不存在：" + String.join(", ", unknown));
        }
        try {
            long id = admin.createUser(me.tenantId(), username, trimToNull(body.displayName()), body.password());
            admin.replaceRoles(me.tenantId(), id, roles);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "username", username, "roles", roles));
        } catch (DuplicateKeyException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "USERNAME_TAKEN", "message", "用户名已存在：" + username));
        }
    }

    record UpdateUser(String displayName, String status, List<String> roles) {
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(@PathVariable long id, @RequestBody(required = false) UpdateUser body) {
        CurrentUser me = requireAdmin();
        if (!admin.userExists(me.tenantId(), id)) {
            return notFound("用户不存在 id=" + id);
        }
        if (body == null) {
            return bad("请求体不能为空");
        }
        if (body.status() != null && !STATUSES.contains(body.status())) {
            return bad("status 只能是 ACTIVE 或 DISABLED");
        }
        if (body.roles() != null) {
            List<String> unknown = admin.unknownRoles(me.tenantId(), body.roles());
            if (!unknown.isEmpty()) {
                return bad("角色不存在：" + String.join(", ", unknown));
            }
        }
        admin.updateProfile(me.tenantId(), id, trimToNull(body.displayName()), body.status());
        if (body.roles() != null) {
            admin.replaceRoles(me.tenantId(), id, body.roles());
        }
        return ResponseEntity.ok(Map.of("id", id));
    }

    // ---------- 角色 ----------

    @GetMapping("/roles")
    public ResponseEntity<?> roles() {
        CurrentUser me = requireAdmin();
        return ResponseEntity.ok(admin.listRoles(me.tenantId()));
    }

    record CreateRole(String code, String name) {
    }

    record RenameRole(String name) {
    }

    /**
     * 新建自定义角色（is_system = false）。
     *
     * <p>code 一旦建好就不能改：授权、代码里的 {@code hasRole(...)}、审计日志都按 code 认人；
     * 想把"审计员"改名成"只读审计"，改 name 就够了。
     */
    @PostMapping("/roles")
    public ResponseEntity<?> createRole(@RequestBody(required = false) CreateRole body) {
        CurrentUser me = requireAdmin();
        if (body == null) {
            return bad("请求体不能为空");
        }
        String code = trimToNull(body.code());
        String name = trimToNull(body.name());
        if (code == null || !ROLE_CODE.matcher(code).matches()) {
            return bad("角色标识只能是大写字母开头的大写字母/数字/下划线，长度 2~32");
        }
        if (name == null || name.length() > 64) {
            return bad("角色名称必填，且不超过 64 字");
        }
        if (admin.roleCodeExists(me.tenantId(), code)) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "ROLE_CODE_TAKEN", "message", "角色标识已存在：" + code));
        }
        long id = admin.createRole(me.tenantId(), code, name);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id, "code", code));
    }

    /** 改角色的显示名（系统内置角色也允许改，code 不变）。 */
    @PutMapping("/roles/{id}")
    public ResponseEntity<?> renameRole(@PathVariable long id, @RequestBody(required = false) RenameRole body) {
        CurrentUser me = requireAdmin();
        if (admin.roleById(me.tenantId(), id) == null) {
            return notFound("角色不存在 id=" + id);
        }
        String name = body == null ? null : trimToNull(body.name());
        if (name == null || name.length() > 64) {
            return bad("角色名称必填，且不超过 64 字");
        }
        admin.renameRole(me.tenantId(), id, name);
        return ResponseEntity.ok(Map.of("id", id, "name", name));
    }

    /**
     * 删自定义角色。
     *
     * <p>两条硬规则：系统内置角色不能删（ADMIN/ANALYST/VIEWER 是代码与种子数据的一部分）；
     * 还有成员的角色不能删（成员会突然失去全部文档权限，属于"安静地改权限"）。
     */
    @DeleteMapping("/roles/{id}")
    public ResponseEntity<?> deleteRole(@PathVariable long id) {
        CurrentUser me = requireAdmin();
        AdminRepository.RoleRow role = admin.roleById(me.tenantId(), id);
        if (role == null) {
            return notFound("角色不存在 id=" + id);
        }
        if (role.system()) {
            return bad("系统内置角色不能删除：" + role.code());
        }
        if (role.userCount() > 0) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "ROLE_IN_USE",
                            "message", "还有 " + role.userCount() + " 个账号属于该角色，先把他们换到别的角色再删"));
        }
        admin.deleteRoleWithGrants(me.tenantId(), id);
        return ResponseEntity.ok(Map.of("id", id, "removed", 1));
    }

    @GetMapping("/roles/{id}/members")
    public ResponseEntity<?> roleMembers(@PathVariable long id,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "10") int size) {
        CurrentUser me = requireAdmin();
        if (!admin.roleExists(me.tenantId(), id)) {
            return notFound("角色不存在 id=" + id);
        }
        return ResponseEntity.ok(admin.membersOfRole(me.tenantId(), id, pageOf(page), sizeOf(size)));
    }

    /** 角色能读哪些文档（管理页「角色详情 → 文档权限」）。 */
    @GetMapping("/roles/{id}/documents")
    public ResponseEntity<?> roleDocuments(@PathVariable long id,
                                           @RequestParam(defaultValue = "1") int page,
                                           @RequestParam(defaultValue = "10") int size) {
        CurrentUser me = requireAdmin();
        if (!admin.roleExists(me.tenantId(), id)) {
            return notFound("角色不存在 id=" + id);
        }
        return ResponseEntity.ok(admin.documentsOfRole(me.tenantId(), id, pageOf(page), sizeOf(size)));
    }

    // ---------- 资源授权 ----------

    @GetMapping("/grants")
    public ResponseEntity<?> grants(@RequestParam(required = false) String q,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        CurrentUser me = requireAdmin();
        return ResponseEntity.ok(admin.listGrants(me.tenantId(), q, pageOf(page), sizeOf(size)));
    }

    /** 文档选择器：新建授权时按标题/公司名搜索。 */
    @GetMapping("/documents")
    public ResponseEntity<?> documents(@RequestParam(required = false) String q,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "10") int size) {
        CurrentUser me = requireAdmin();
        return ResponseEntity.ok(admin.searchDocuments(me.tenantId(), q, pageOf(page), sizeOf(size)));
    }

    record CreateGrant(String subjectType, Long subjectId, String resourceType, Long resourceId) {
    }

    @PostMapping("/grants")
    public ResponseEntity<?> createGrant(@RequestBody(required = false) CreateGrant body) {
        CurrentUser me = requireAdmin();
        if (body == null || body.subjectId() == null || body.resourceId() == null) {
            return bad("subjectId 与 resourceId 必填");
        }
        String subjectType = body.subjectType() == null ? "ROLE" : body.subjectType();
        String resourceType = body.resourceType() == null ? "DOCUMENT" : body.resourceType();
        if (!SUBJECT_TYPES.contains(subjectType)) {
            return bad("subjectType 只能是 ROLE 或 USER");
        }
        if (!RESOURCE_TYPES.contains(resourceType)) {
            return bad("resourceType 只能是 DOCUMENT 或 COMPANY");
        }
        boolean subjectOk = "ROLE".equals(subjectType)
                ? admin.roleExists(me.tenantId(), body.subjectId())
                : admin.userExists(me.tenantId(), body.subjectId());
        if (!subjectOk) {
            return bad("授权对象不存在：" + subjectType + " " + body.subjectId());
        }
        boolean resourceOk = "DOCUMENT".equals(resourceType)
                ? admin.documentExists(me.tenantId(), body.resourceId())
                : admin.companyExists(me.tenantId(), body.resourceId());
        if (!resourceOk) {
            return bad("授权资源不存在：" + resourceType + " " + body.resourceId());
        }
        // 全租户可见（TENANT）的文档只认「整家公司」授权：AclScopeAuthorizer 对 TENANT 文档
        // 只查 resource_type='COMPANY'，给出 DOCUMENT 授权不会让它变得可读，属于静默无效，必须拒掉。
        if ("DOCUMENT".equals(resourceType) && "TENANT".equals(admin.documentVisibility(me.tenantId(), body.resourceId()))) {
            return bad("该文档是全租户可见，单份授权不会生效；请改为对所属公司授权（resourceType=COMPANY）");
        }
        long id = admin.createGrant(me.tenantId(), subjectType, body.subjectId(), resourceType,
                body.resourceId(), me.userId());
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("id", id));
    }

    @DeleteMapping("/grants/{id}")
    public ResponseEntity<?> deleteGrant(@PathVariable long id) {
        CurrentUser me = requireAdmin();
        int removed = admin.deleteGrant(me.tenantId(), id);
        if (removed == 0) {
            return notFound("授权不存在 id=" + id);
        }
        return ResponseEntity.ok(Map.of("id", id, "removed", removed));
    }

    // ---------- 守卫与错误 ----------

    private static CurrentUser requireAdmin() {
        CurrentUser me = TenantContext.require();
        if (!me.isAdmin()) {
            throw new AdminRequiredException();
        }
        return me;
    }

    private static int pageOf(int page) {
        return Math.max(1, page);
    }

    private static int sizeOf(int size) {
        return Math.min(Math.max(1, size), MAX_PAGE_SIZE);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseEntity<Object> bad(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", "BAD_REQUEST", "message", message));
    }

    private static ResponseEntity<Object> notFound(String message) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "NOT_FOUND", "message", message));
    }

    /** 有身份但没这个权限：403，前端据此显示「需要管理员」，而不是把人踢回登录页。 */
    static class AdminRequiredException extends RuntimeException {
    }

    @ExceptionHandler(AdminRequiredException.class)
    public ResponseEntity<Object> forbidden(AdminRequiredException e) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "ADMIN_REQUIRED");
        body.put("message", "需要管理员权限");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<Object> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", e.code(), "message", "需要登录"));
    }
}
