"""Synthetic reports prove coverage gates fail closed without infrastructure."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("coverage_gate", Path(__file__).with_name("check-coverage.py"))
GATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(GATE)


class CoverageGateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)

    def write(self, path, content):
        file = self.root / path
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_text(content)
        return file

    def java(self, module="domain", lines=95, branches=90, missing="", reported="Example.java"):
        self.write(f"backend/{module}/src/main/java/example/Example.java", "package example; class Example {}")
        report = '<report><package name="example"><sourcefile name="' + reported + '"/></package>'
        for kind, covered in (("LINE", lines), ("BRANCH", branches)):
            if kind != missing:
                report += f'<counter type="{kind}" covered="{covered}" missed="{100-covered}"/>'
        return self.write(f"backend/{module}/target/site/jacoco/jacoco.xml", report + "</report>")

    def angular(self, line_hits=(1,) * 9 + (0,), branch_hits=(1,) * 9 + (0,)):
        source = self.write("frontend/src/app/example.ts", "export const example = true;")
        entry = {"s": {}, "statementMap": {}, "b": {"0": list(branch_hits)},
                 "branchMap": {"0": {"locations": [{} for _ in branch_hits]}}}
        for index, hits in enumerate(line_hits):
            entry["s"][str(index)] = hits
            entry["statementMap"][str(index)] = {"start": {"line": index + 1}}
        return self.write("frontend/coverage/coverage-final.json", json.dumps({str(source): entry}))

    def test_empty_scaffolds_have_no_coverage_claim(self):
        self.assertEqual([], GATE.validate(self.root))

    def test_domain_at_exact_boundary_passes(self):
        self.java()
        self.assertEqual(["domain LINE: 95/100", "domain BRANCH: 90/100"], GATE.validate(self.root))

    def test_stronger_application_line_threshold_is_retained(self):
        self.java("application", lines=94)
        with self.assertRaisesRegex(ValueError, "below 95%"):
            GATE.validate(self.root)

    def test_all_backend_modules_require_ninety_percent_branches(self):
        for module in GATE.BACKEND:
            with self.subTest(module=module):
                self.java(module, lines=100, branches=89)
                with self.assertRaisesRegex(ValueError, "BRANCH"):
                    GATE.validate(self.root)
                self.java(module, lines=100, branches=90)

    def test_adapters_and_bootstrap_require_ninety_percent_lines(self):
        for module in ("adapters", "bootstrap"):
            with self.subTest(module=module):
                self.java(module, lines=89)
                with self.assertRaisesRegex(ValueError, "below 90%"):
                    GATE.validate(self.root)
                self.java(module, lines=90)

    def test_healthy_module_cannot_mask_failing_module(self):
        self.java("domain", lines=100, branches=100)
        self.java("adapters", lines=89)
        with self.assertRaisesRegex(ValueError, "adapters LINE"):
            GATE.validate(self.root)

    def test_missing_report_fails(self):
        self.java().unlink()
        with self.assertRaisesRegex(ValueError, "Missing coverage report"):
            GATE.validate(self.root)

    def test_missing_counter_fails(self):
        self.java(missing="BRANCH")
        with self.assertRaisesRegex(ValueError, "Missing or duplicate BRANCH"):
            GATE.validate(self.root)

    def test_uninstrumented_production_source_fails(self):
        self.java()
        self.write("backend/domain/src/main/java/example/Omitted.java", "class Omitted {}")
        with self.assertRaisesRegex(ValueError, "Unreported production sources"):
            GATE.validate(self.root)

    def test_straight_line_module_can_have_no_branches(self):
        report = self.java(missing="BRANCH")
        report.write_text(report.read_text().replace('<sourcefile name="Example.java"/>',
                          '<sourcefile name="Example.java"><line nr="1" mb="0" cb="0"/></sourcefile>'))
        self.assertIn("domain BRANCH: 0/0", GATE.validate(self.root))

    def test_missing_branch_counter_cannot_hide_executable_branches(self):
        report = self.java(missing="BRANCH")
        report.write_text(report.read_text().replace('<sourcefile name="Example.java"/>',
                          '<sourcefile name="Example.java"><line nr="1" mb="1" cb="1"/></sourcefile>'))
        with self.assertRaisesRegex(ValueError, "Missing or duplicate BRANCH"):
            GATE.validate(self.root)

    def test_class_source_evidence_covers_interface_source(self):
        report = self.java()
        report.write_text(report.read_text().replace('<sourcefile name="Example.java"/>',
                          '<class name="example/Example" sourcefilename="Example.java"/>'))
        self.assertEqual(2, len(GATE.validate(self.root)))

    def test_empty_line_report_fails(self):
        report = self.java()
        report.write_text(report.read_text().replace('covered="95" missed="5"', 'covered="0" missed="0"'))
        with self.assertRaisesRegex(ValueError, "Empty coverage"):
            GATE.validate(self.root)

    def test_negative_counters_fail(self):
        self.java(lines=-1)
        with self.assertRaisesRegex(ValueError, "Negative LINE"):
            GATE.validate(self.root)

    def test_frontend_exact_boundary_passes(self):
        self.angular()
        self.assertEqual(["Angular LINE: 9/10", "Angular BRANCH: 9/10"], GATE.validate(self.root))

    def test_frontend_lines_below_boundary_fail(self):
        self.angular(line_hits=(1,) * 8 + (0, 0))
        with self.assertRaisesRegex(ValueError, "Angular LINE"):
            GATE.validate(self.root)

    def test_frontend_branches_below_boundary_fail(self):
        self.angular(branch_hits=(1,) * 8 + (0, 0))
        with self.assertRaisesRegex(ValueError, "Angular BRANCH"):
            GATE.validate(self.root)

    def test_frontend_source_without_report_fails(self):
        self.angular().unlink()
        with self.assertRaisesRegex(ValueError, "Missing Angular"):
            GATE.validate(self.root)

    def test_frontend_omitted_source_fails(self):
        self.angular()
        self.write("frontend/src/main.ts", "console.log('synthetic');")
        with self.assertRaisesRegex(ValueError, "Unreported Angular production sources"):
            GATE.validate(self.root)

    def test_frontend_generated_directory_alone_does_not_exclude_source(self):
        self.angular()
        self.write("frontend/src/app/shared/generated/client.ts", "export const client = 1;")
        with self.assertRaisesRegex(ValueError, "Unreported Angular"):
            GATE.validate(self.root)

    def test_only_manifest_identified_generated_files_are_excluded(self):
        self.angular()
        base = "frontend/src/app/shared/generated/"
        self.write(base + "client.ts", "export const client = 1;")
        self.write(base + ".openapi-generator/FILES", "client.ts\n")
        self.write(base + ".openapi-generator/VERSION", "synthetic-generator\n")
        self.assertEqual(2, len(GATE.validate(self.root)))
        self.write(base + "handwritten.ts", "export const handwritten = 2;")
        with self.assertRaisesRegex(ValueError, "Unreported Angular"):
            GATE.validate(self.root)

    def test_manifest_cannot_exclude_handwritten_parent_files(self):
        self.angular()
        base = "frontend/src/app/shared/generated/.openapi-generator/"
        self.write(base + "FILES", "../../../example.ts\n")
        self.write(base + "VERSION", "synthetic-generator\n")
        with self.assertRaisesRegex(ValueError, "escapes"):
            GATE.validate(self.root)

    def test_incomplete_frontend_branch_map_fails(self):
        report = self.angular()
        data = json.loads(report.read_text())
        next(iter(data.values()))["branchMap"]["0"]["locations"].pop()
        report.write_text(json.dumps(data))
        with self.assertRaisesRegex(ValueError, "Incomplete Angular branches"):
            GATE.validate(self.root)


if __name__ == "__main__":
    unittest.main()
