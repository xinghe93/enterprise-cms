import base64
import json
import unittest
from unittest.mock import patch

import deepseek_review as review


FILE = "frontend/src/api/content.js"
PATCH = """@@ -1,0 +1,2 @@
+const categories = '/admin/categories'
+export const listCategories = (params) => api.get(categories, { params })
"""
FILES = [{"filename": FILE, "patch": PATCH}]
SPEC = {
    "paths": {
        "/api/admin/categories": {"get": {
            "parameters": [{"name": "status", "in": "query", "schema": {"type": "integer"}}],
            "responses": {"200": {"content": {"application/json": {
                "schema": {"$ref": "#/components/schemas/CategoryListResponse"},
            }}}},
        }},
        "/api/admin/tags": {"get": {"parameters": [{"name": "current"}]}},
    },
    "components": {"schemas": {"CategoryListResponse": {
        "type": "object", "properties": {"data": {"$ref": "#/components/schemas/CategoryList"}},
    }, "CategoryList": {"type": "array", "items": {"type": "object"}}}},
}


class ReviewRiskTests(unittest.TestCase):
    def finding(self, **changes):
        return {
            "file": FILE, "line": 2, "title": "分类查询",
            "severity": "高", "problem": "分类查询可能漏传分页",
            **changes,
        }

    def test_category_pagination_speculation_does_not_raise_risk(self):
        finding = self.finding(status="needs_verification")
        with patch.object(review, "analyze_batch", return_value={"findings": [finding]}):
            result = review.review_files("PR #31", FILES)

        self.assertEqual(result["risk_level"], "低")
        self.assertEqual(result["findings"], [])
        self.assertEqual(len(result["needs_verification"]), 1)
        report = review.render_review(result, "PR #31", "")
        self.assertIn("待核实", report)
        self.assertIn("分类查询可能漏传分页", report)
        self.assertNotIn("**[高]**", report)

    def test_high_finding_requires_quote_on_the_reported_line(self):
        base = dict(status="supported", trigger="打开分类页", impact="分类页面完全无法使用")
        for quote in ("api.get(tags", "const categories"):
            with self.subTest(quote=quote):
                finding = self.finding(**base, evidence=quote)
                with patch.object(review, "analyze_batch", return_value={"findings": [finding]}):
                    result = review.review_files("PR #31", FILES)
                self.assertEqual(result["risk_level"], "低")
                self.assertEqual(len(result["needs_verification"]), 1)

    def test_high_finding_with_evidence_retains_severity(self):
        path = "backend/src/main/java/example/SampleController.java"
        code = "return userRepository.findAll();"
        files = [{"filename": path, "patch": "@@ -10 +10 @@\n-return List.of();\n+" + code}]
        finding = self.finding(
            file=path, line=10, title="匿名接口返回用户数据", status="supported", evidence=code,
            trigger="匿名请求调用公开用户接口", impact="响应泄露全量用户资料",
        )
        with patch.object(review, "analyze_batch", return_value={"findings": [finding]}):
            result = review.review_files("sample PR", files)
        self.assertEqual(result["risk_level"], "高")
        self.assertEqual(result["needs_verification"], [])
        self.assertIn(code, review.render_inline_comment(result["findings"][0]))

    def test_contract_context_matches_route_and_includes_array_response(self):
        payload = {"encoding": "base64", "content": base64.b64encode(json.dumps(SPEC).encode()).decode()}
        with patch.object(review, "github_request", return_value=payload) as fetch, \
                patch.object(review, "analyze_batch", return_value={"findings": []}) as analyze:
            review.review_files("PR #31", FILES, revision="a" * 40)
        fetch.assert_called_once()
        self.assertIn("ref=" + "a" * 40, fetch.call_args.args[1])
        context = analyze.call_args.kwargs["api_context"]
        self.assertIn("/api/admin/categories", context)
        self.assertIn('"status"', context)
        self.assertIn('"type":"array"', context)
        self.assertNotIn("/api/admin/tags", context)
        self.assertNotIn('"current"', context)

    def test_malformed_openapi_still_runs_review(self):
        payload = {"content": base64.b64encode(b'{"paths": []}').decode()}
        with patch.object(review, "github_request", return_value=payload), \
                patch.object(review, "analyze_batch", return_value={"findings": []}) as analyze:
            result = review.review_files("PR #31", FILES, revision="a" * 40)
        self.assertEqual(result["risk_level"], "低")
        self.assertEqual(analyze.call_args.kwargs["api_context"], "")

    def test_missing_openapi_still_runs_review(self):
        with patch.object(review, "github_request", side_effect=review.GitHubAPIError(404, "")), \
                patch.object(review, "analyze_batch", return_value={"findings": []}) as analyze:
            result = review.review_files("PR #31", FILES, revision="a" * 40)
        self.assertEqual(result["risk_level"], "低")
        self.assertEqual(analyze.call_args.kwargs["api_context"], "")


if __name__ == "__main__":
    unittest.main()
