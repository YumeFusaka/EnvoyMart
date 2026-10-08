package yumefusaka.envoymart.aiservice.controller;

import org.springframework.web.bind.annotation.*;
import yumefusaka.envoymart.aiservice.memory.BadCaseStore;
import yumefusaka.envoymart.common.result.Result;
import yumefusaka.envoymart.common.web.IdentityHeaderInterceptor;
import yumefusaka.envoymart.common.web.RequireAdmin;

import java.util.List;

@RequireAdmin
@RestController
@RequestMapping("/ai/admin/bad-cases")
public class BadCaseAdminController {
    private final BadCaseStore store;
    public BadCaseAdminController(BadCaseStore store) { this.store = store; }
    @GetMapping public Result<List<BadCaseStore.BadCase>> list(@RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "100") int size) {
        return Result.success(store.listPage(page, size).stream().map(store::adminView).toList());
    }
    @GetMapping("/{id}") public Result<BadCaseStore.BadCase> detail(@PathVariable String id) {
        try { return Result.success(store.adminView(store.findForAdmin(id))); }
        catch (Exception e) { return Result.error(404, "反馈不存在"); }
    }
    @GetMapping("/fixtures") public Result<List<BadCaseStore.Fixture>> fixtures(@RequestParam(defaultValue = "100") int limit) {
        return Result.success(store.fixtures(limit));
    }
    @PostMapping("/{id}/review") public Result<BadCaseStore.BadCase> review(
            @PathVariable String id, @RequestParam BadCaseStore.Status status,
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String reviewer) {
        try { return Result.success(store.adminView(store.review(id, status, reviewer))); } catch (IllegalArgumentException e) { return Result.error(400, e.getMessage()); }
    }
    @PostMapping("/{id}/test-case") public Result<BadCaseStore.BadCase> addTestCase(
            @PathVariable String id, @RequestBody BadCaseStore.FixtureAnnotation annotation,
            @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String reviewer) {
        try { return Result.success(store.adminView(store.addTestCase(id, reviewer, annotation))); } catch (IllegalArgumentException e) { return Result.error(400, e.getMessage()); }
    }
    @DeleteMapping("/{id}/test-case") public Result<Void> removeTestCase(
            @PathVariable String id, @RequestHeader(IdentityHeaderInterceptor.USER_ID_HEADER) String reviewer) {
        try { return store.removeTestCase(id, reviewer) ? Result.success() : Result.error(404, "追加测试集记录不存在"); }
        catch (IllegalArgumentException e) { return Result.error(400, e.getMessage()); }
    }
}
