#!/usr/bin/env python3
"""Run an implemented Python suite, failing for empty, failed or skipped tests."""
import argparse
import unittest


def successful(result):
    return result.testsRun > 0 and not result.skipped and not result.expectedFailures and result.wasSuccessful()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--start', required=True)
    args = parser.parse_args()
    suite = unittest.defaultTestLoader.discover(args.start, pattern='test_*.py')
    result = unittest.TextTestRunner(verbosity=2).run(suite)
    if not successful(result):
        raise SystemExit('Missing, failed or skipped Python tests')


if __name__ == '__main__':
    main()
