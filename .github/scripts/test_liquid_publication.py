#!/usr/bin/env python3
"""Exercise the real preview-request gate without network, secrets or releases."""
from base64 import b64encode
from copy import deepcopy
import json
import os
from pathlib import Path
import subprocess
import tempfile
import textwrap
import unittest
from unittest.mock import patch

WORKFLOW = Path(__file__).resolve().parents[1] / 'workflows/publish-liquid-preview.yml'
TEXT = WORKFLOW.read_text()
START = TEXT.index('          import base64, json, os, re, subprocess\n')
END = TEXT.index('          PY\n', START)
CODE = compile(textwrap.dedent(TEXT[START:END]), str(WORKFLOW) + ':request-gate', 'exec')
REPO = 'repos/example/fixture'
BEFORE, HEAD, SOURCE = 'b' * 40, 'c' * 40, 'a' * 40
REQUEST_PATH = '.github/liquid-preview-request.json'


class PublicationGateTest(unittest.TestCase):
    def setUp(self):
        self.env = {
            'GH_REPO': 'example/fixture', 'GITHUB_EVENT_NAME': 'push',
            'PUSH_BEFORE': BEFORE, 'GITHUB_SHA': HEAD,
            'REQUEST_RUN': '123', 'REQUEST_SHA': SOURCE, 'REQUEST_CONFIRM': 'true',
        }
        self.request = {'source_run': 123, 'source_sha': SOURCE,
                        'confirm_preview': True, 'ui_reviewed': True}
        self.push_diff = {'status': 'ahead', 'behind_by': 0, 'total_commits': 1,
                          'files': [{'filename': REQUEST_PATH, 'status': 'added'}]}
        self.source_diff = {'status': 'ahead', 'behind_by': 0, 'total_commits': 2,
                            'files': [{'filename': REQUEST_PATH, 'status': 'added'}]}
        self.built = {'path': '.github/workflows/build-liquid.yml', 'head_sha': SOURCE,
                      'head_branch': 'arena/dda4cc57-anoymundo',
                      'status': 'completed', 'conclusion': 'success'}
        self.jobs = [{'name': name, 'conclusion': 'success'}
                     for name in ('build', 'visual-qa (23)', 'visual-qa (33)')]
        self.artifacts = [{'name': name, 'expired': False, 'size_in_bytes': 1}
                          for name in ('Liquid-APKs', 'Liquid-UI-API-23', 'Liquid-UI-API-33')]

    def fake_api(self, args):
        self.assertEqual(args[:2], ['gh', 'api'])
        path = args[2].removeprefix(REPO)
        rows = {
            f'/compare/{BEFORE}...{HEAD}': self.push_diff,
            f'/compare/{SOURCE}...{HEAD}': self.source_diff,
            f'/contents/{REQUEST_PATH}?ref={HEAD}': {
                'encoding': 'base64',
                'content': b64encode(json.dumps(self.request).encode()).decode(),
            },
            '/actions/runs/123': self.built,
            '/actions/runs/123/jobs': {'jobs': self.jobs},
            '/actions/runs/123/artifacts': {'artifacts': self.artifacts},
        }
        self.assertIn(path, rows, 'Unexpected API request')
        return json.dumps(rows[path]).encode()

    def execute(self, rejected=False):
        with tempfile.TemporaryDirectory(prefix='liquid-publication-test-') as folder:
            output = Path(folder) / 'output.txt'
            output.touch()
            env = dict(self.env, GITHUB_OUTPUT=str(output))
            with patch.dict(os.environ, env), patch.object(subprocess, 'check_output', self.fake_api), patch('builtins.print'):
                if rejected:
                    with self.assertRaises(AssertionError):
                        exec(CODE, {})
                else:
                    exec(CODE, {})
            result = output.read_text()
            if rejected:
                self.assertEqual(result, '', 'A rejected request must not approve publication')
            return result

    def test_explicit_reviewed_push_approves_only_named_source(self):
        self.assertEqual(self.execute(), 'approved=true\nsource_run=123\nsource_sha=' + SOURCE + '\n')

    def test_definition_only_push_does_not_publish(self):
        self.push_diff['files'] = [{'filename': '.github/workflows/publish-liquid-preview.yml', 'status': 'modified'}]
        self.assertEqual(self.execute(), '')

    def test_deleted_request_does_not_publish(self):
        self.push_diff['files'][0]['status'] = 'removed'
        self.assertEqual(self.execute(), '')

    def test_unconfirmed_manual_run_does_not_publish(self):
        self.env.update(GITHUB_EVENT_NAME='workflow_dispatch', REQUEST_CONFIRM='false')
        self.assertEqual(self.execute(), '')

    def test_confirmed_manual_run_still_works(self):
        self.env['GITHUB_EVENT_NAME'] = 'workflow_dispatch'
        self.assertIn('approved=true\n', self.execute())

    def test_review_is_required(self):
        self.request['ui_reviewed'] = False
        self.execute(rejected=True)

    def test_preview_confirmation_is_required(self):
        self.request['confirm_preview'] = False
        self.execute(rejected=True)

    def test_failed_build_is_rejected(self):
        self.built['conclusion'] = 'failure'
        self.execute(rejected=True)

    def test_both_apis_are_required(self):
        self.jobs.pop()
        self.execute(rejected=True)

    def test_other_branch_is_rejected(self):
        self.built['head_branch'] = 'main'
        self.execute(rejected=True)

    def test_changed_liquid_source_is_rejected(self):
        self.source_diff['files'].append({'filename': 'liquid/app/build.gradle', 'status': 'modified'})
        self.execute(rejected=True)

    def test_changed_build_workflow_is_rejected(self):
        self.source_diff['files'].append({'filename': '.github/workflows/build-liquid.yml', 'status': 'modified'})
        self.execute(rejected=True)

    def test_expired_evidence_is_rejected(self):
        self.artifacts[-1]['expired'] = True
        self.execute(rejected=True)

    def test_truncated_diff_is_rejected(self):
        self.source_diff['files'] = deepcopy(self.source_diff['files']) * 300
        self.execute(rejected=True)

    def test_invalid_source_is_rejected(self):
        self.request['source_sha'] = 'not-a-source-sha'
        self.execute(rejected=True)

    def test_no_history_push_is_rejected(self):
        self.env['PUSH_BEFORE'] = '0' * 40
        self.execute(rejected=True)

    def test_workflow_branch_and_approval_conditions_remain_bound(self):
        self.assertIn("if: github.ref == 'refs/heads/arena/dda4cc57-anoymundo'", TEXT)
        self.assertIn("if: needs.verify-request.outputs.approved == 'true'", TEXT)
        self.assertIn('SOURCE_RUN: ${{ needs.verify-request.outputs.source_run }}', TEXT)
        self.assertIn('SOURCE_SHA: ${{ needs.verify-request.outputs.source_sha }}', TEXT)
        self.assertEqual(TEXT.count('run-id: ${{ needs.verify-request.outputs.source_run }}'), 2)


if __name__ == '__main__':
    unittest.main()
