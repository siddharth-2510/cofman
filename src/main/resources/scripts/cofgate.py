#!/usr/bin/env python3
"""
config_scanner.py

Scans git diff between branches for added lines that contain:
    findByDomainNameAndType("domain", "type")

Then checks if corresponding config exists in a registry repo:
    {registry_path}/{lob}/{domainName}/{domainType}/uat.txt, demo.txt, prod.txt
    {registry_path}/default/{domainName}/{domainType}/uat.txt, demo.txt, prod.txt

Exit codes:
    0 -> all configs exist or no configs detected
    1 -> one or more configs missing
"""

import argparse
import os
import re
import sys
import subprocess


ENVIRONMENTS = ["uat", "demo", "prod"]


def parse_args():
    """
    Parse command line arguments for branch names, LOB, repo path, and registry path.
    """
    parser = argparse.ArgumentParser(
        description="Scan git diff for findByDomainNameAndType calls and verify config files exist."
    )
    parser.add_argument(
        "--feature-branch",
        "-f",
        required=True,
        help="Feature branch to compare (the branch with changes)",
    )
    parser.add_argument(
        "--master-branch",
        "-m",
        default="main",
        help="Master/main branch to compare against (default: main)",
    )
    parser.add_argument(
        "--lob",
        "-l",
        required=True,
        help="Line of Business - used for path: /{lob}/{domainName}/{domainType}/",
    )
    parser.add_argument(
        "--repo-path",
        "-p",
        required=True,
        help="Path to the source code repository (where git diff will run)",
    )
    parser.add_argument(
        "--registry-path",
        "-r",
        required=True,
        help="Path to registry repo where config files are stored",
    )

    args = parser.parse_args()

    # Validate paths exist
    if not os.path.isdir(args.repo_path):
        print(f"ERROR: Source repo path does not exist: {args.repo_path}")
        sys.exit(1)

    if not os.path.isdir(os.path.join(args.repo_path, ".git")):
        print(f"ERROR: Source repo path is not a git repository: {args.repo_path}")
        sys.exit(1)

    if not os.path.isdir(args.registry_path):
        print(f"ERROR: Registry path does not exist: {args.registry_path}")
        sys.exit(1)

    return args


def get_added_lines_from_git_diff(repo_path, master_branch, feature_branch):
    """
    Runs git diff between master and feature branch and returns only added lines.
    Excludes diff metadata like +++ b/file.

    Args:
        repo_path: Path to the git repository where diff will be run
        master_branch: The base branch (e.g., main, master)
        feature_branch: The feature branch with changes
    """
    try:
        result = subprocess.run(
            ["git", "-C", repo_path, "diff", f"{master_branch}...{feature_branch}"],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            check=True,
        )
    except subprocess.CalledProcessError as e:
        print(f"ERROR: Failed to run git diff {master_branch}...{feature_branch} in {repo_path}")
        print(e.stderr)
        sys.exit(1)

    added_lines = []
    for line in result.stdout.splitlines():
        # Only real added lines, not diff headers
        if line.startswith("+") and not line.startswith("+++"):
            added_lines.append(line[1:])  # strip leading '+'

    return added_lines


def extract_domain_pairs(lines):
    """
    Extracts (domainName, domainType) from lines matching:
        findByDomainNameAndType("x", "y")
        findByDomainNameAndType('x', 'y')

    Deduplicates results using a set.
    """
    pattern = re.compile(
        r'findByDomainNameAndType\s*\(\s*["\']([^"\']+)["\']\s*,\s*["\']([^"\']+)["\']\s*\)'
    )

    results = set()

    for line in lines:
        for match in pattern.finditer(line):
            domain_name, domain_type = match.groups()
            results.add((domain_name, domain_type))

    return results


def check_env_files(base_path, domain_name, domain_type):
    """
    Check if uat.txt, demo.txt, prod.txt exist in the given path.
    Returns dict with env -> (exists, full_path)
    """
    results = {}
    for env in ENVIRONMENTS:
        file_path = os.path.join(base_path, domain_name, domain_type, f"{env}.txt")
        results[env] = (os.path.isfile(file_path), file_path)
    return results


def check_registry_configs(registry_path, lob, domain_pairs):
    """
    Checks existence of config files in both LOB and default paths:
        {registry_path}/{lob}/{domainName}/{domainType}/uat.txt, demo.txt, prod.txt
        {registry_path}/default/{domainName}/{domainType}/uat.txt, demo.txt, prod.txt
    """
    results = []

    lob_base_path = os.path.join(registry_path, lob)
    default_base_path = os.path.join(registry_path, "default")

    for domain_name, domain_type in domain_pairs:
        domain_label = f"{domain_name}/{domain_type}"

        # Check LOB path
        lob_results = check_env_files(lob_base_path, domain_name, domain_type)

        # Check default path
        default_results = check_env_files(default_base_path, domain_name, domain_type)

        results.append({
            "domain_name": domain_name,
            "domain_type": domain_type,
            "label": domain_label,
            "lob": lob_results,
            "default": default_results,
        })

    return results


def print_results(results, lob):
    """
    Print results in a readable format.
    """
    all_ok = True

    for item in sorted(results, key=lambda x: x["label"]):
        print(f"\n{'='*60}")
        print(f"Domain: {item['label']}")
        print(f"{'='*60}")

        # LOB results
        print(f"\n  [{lob.upper()}] Path:")
        for env in ENVIRONMENTS:
            exists, path = item["lob"][env]
            status = "✓ OK" if exists else "✗ MISSING"
            if not exists:
                all_ok = False
            print(f"    {env.upper():6} : {status}")
            if not exists:
                print(f"             Expected: {path}")

        # Default results
        print(f"\n  [DEFAULT] Path (all values present indicator):")
        default_all_present = all(item["default"][env][0] for env in ENVIRONMENTS)

        for env in ENVIRONMENTS:
            exists, path = item["default"][env]
            status = "✓ OK" if exists else "✗ MISSING"
            print(f"    {env.upper():6} : {status}")

        if default_all_present:
            print(f"\n  ✓ All default configs present for {item['label']}")
        else:
            print(f"\n  ⚠ Some default configs missing for {item['label']}")

    return all_ok


def main():
    args = parse_args()

    print(f"Source Repo: {args.repo_path}")
    print(f"Scanning git diff: {args.master_branch}...{args.feature_branch}")
    print(f"LOB: {args.lob}")
    print(f"Registry Path: {args.registry_path}")

    added_lines = get_added_lines_from_git_diff(args.repo_path, args.master_branch, args.feature_branch)
    print(added_lines)
    domain_pairs = extract_domain_pairs(added_lines)

    # Edge case: no matching configs found in diff
    if not domain_pairs:
        print("\nNo findByDomainNameAndType calls detected in diff")
        sys.exit(0)

    print(f"\nFound {len(domain_pairs)} domain config(s) in diff:")
    for dn, dt in sorted(domain_pairs):
        print(f"  - {dn}/{dt}")

    results = check_registry_configs(args.registry_path, args.lob, domain_pairs)

    all_ok = print_results(results, args.lob)

    print(f"\n{'='*60}")
    if all_ok:
        print("✓ All LOB config files exist!")
        sys.exit(0)
    else:
        print("✗ Some LOB config files are missing!")
        sys.exit(1)


if __name__ == "__main__":
    main()
