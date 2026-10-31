import copy
import unittest
from scripts import public_pushup_benchmark as benchmark


def case(identifier='a'):
    return dict(id=identifier, source_name=identifier+'.mp4', dataset='repcount_a', sha256=identifier*64,
        role='test', source_group=identifier, view='side', count=2, expected_frames=100,
        source_span_seconds=[0, 10], labels=dict(count_window_seconds=[1, 9], cycle_end_seconds=[4, 8]))


def report(identifier='a', status='completed', events=(4, 8)):
    return dict(id=identifier, sourceSHA256=identifier*64, status=status, frames=100,
        events=[dict(returnedAt=t) for t in events], firstReadyAt=1, sets=[])


class PublicPushUpBenchmarkTests(unittest.TestCase):
    def test_missing_and_failed_sources_remain_in_the_denominator(self):
        manifest = dict(cases=[case('a'), case('b'), case('c')])
        result = benchmark.score(manifest, [report(), report('b', status='failed')])
        self.assertEqual(result['summary']['videos'], 3)
        self.assertEqual(result['summary']['exact_videos'], 1)
        self.assertEqual(result['summary']['completed_sources'], 1)
        self.assertEqual(result['summary']['expected'], 6)
        self.assertEqual(result['cases'][-1]['status'], 'missing')

    def test_late_readiness_does_not_remove_missed_references_or_hide_extras(self):
        native = report(events=(.5, 8, 9.5)); native['firstReadyAt'] = 7
        result = benchmark.score(dict(cases=[case()]), [native])['cases'][0]
        self.assertEqual(result['expected'], 2)
        self.assertEqual(result['predicted'], 1)
        self.assertEqual(result['undercount'], 1)
        self.assertEqual(result['outside_labelled_window'], 2)
        self.assertFalse(result['exact_count'])

    def test_matching_totals_cannot_hide_an_incomplete_frame_run(self):
        native = report(); native['frames'] = 99
        row = benchmark.score(dict(cases=[case()]), [native])['cases'][0]
        self.assertEqual(row['predicted'], row['expected'])
        self.assertFalse(row['source_frame_count_matches'])
        self.assertFalse(row['exact_count'])

    def test_wrong_media_duplicate_and_unknown_reports_are_rejected(self):
        wrong = report(); wrong['sourceSHA256'] = 'f'*64
        for reports in [[wrong], [report(), report()], [report('b')]]:
            with self.subTest(reports=reports), self.assertRaises(AssertionError):
                benchmark.score(dict(cases=[case()]), reports)

    def test_source_families_cannot_cross_roles_and_screened_cases_cannot_disappear(self):
        a, b = case(), case('b')
        spec = dict(scope='public_pushup_benchmark_v1', cases=[a,b],
                    screening=[dict(id=c['id'], included=True) for c in [a,b]])
        benchmark.validate(spec)
        leaked = copy.deepcopy(spec)
        leaked['cases'][1].update(source_group='a', role='development')
        with self.assertRaises(AssertionError): benchmark.validate(leaked)
        spec['cases'].pop()
        with self.assertRaises(AssertionError): benchmark.validate(spec)


if __name__ == '__main__': unittest.main()
