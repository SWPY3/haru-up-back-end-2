#!/usr/bin/env python3
"""Aggregate OPENAI_TELEMETRY console logs; no credentials or network needed."""
import argparse
import json
import sys
from collections import Counter, defaultdict
from decimal import Decimal

PREFIX = "OPENAI_TELEMETRY "


def summarize(lines):
    calls = []
    validations = defaultdict(list)
    malformed = 0
    for line in lines:
        if PREFIX not in line:
            continue
        try:
            event = json.loads(line.split(PREFIX, 1)[1], parse_float=Decimal)
        except (ValueError, TypeError):
            malformed += 1
            continue
        if event.get("event") == "api_call":
            calls.append(event)
        elif event.get("event") == "validation":
            validations[event["operationId"]].append(event)

    groups = defaultdict(list)
    for call in calls:
        groups[(call["environment"], call["feature"])].append(call)
    result = []
    for (environment, feature), rows in sorted(groups.items()):
        operations = defaultdict(list)
        for row in rows:
            operations[row["operationId"]].append(row)
        costs = [Decimal(str(row["estimatedCostUsd"])) for row in rows if row.get("estimatedCostUsd") is not None]
        incomplete = sum(any(row.get("estimatedCostUsd") is None for row in attempts) for attempts in operations.values())
        reasons = Counter(reason for op in operations for v in validations[op]
                          if v["outcome"] == "rejected" for reason in v.get("reasons", []))
        result.append({
            "environment": environment, "feature": feature,
            "calls": len(rows), "operations": len(operations),
            "retryCalls": sum(row["attempt"] > 1 for row in rows),
            "failedApiCalls": sum(row["outcome"] == "failed" for row in rows),
            "inputTokensReported": sum(row.get("inputTokens") or 0 for row in rows),
            "cachedInputTokensReported": sum(row.get("cachedInputTokens") or 0 for row in rows),
            "outputTokensReported": sum(row.get("outputTokens") or 0 for row in rows),
            "unknownUsageCalls": sum(row.get("usageStatus") != "reported" for row in rows),
            "unknownCachedInputCalls": sum(row.get("cachedInputTokens") is None for row in rows),
            "unknownCostCalls": len(rows) - len(costs),
            "knownEstimatedCostUsd": str(sum(costs, Decimal(0))),
            # Do not report a partial total as a full average operation cost.
            "averageOperationCostUsd": None if incomplete else str(sum(costs, Decimal(0)) / len(operations)),
            "operationsWithUnknownCost": incomplete,
            "fallbackOperations": sum(any(v["outcome"] == "fallback" for v in validations[op]) for op in operations),
            "operationsWithValidation": sum(bool(validations[op]) for op in operations),
            "firstAttemptPassedOperations": sum(any(v["outcome"] == "passed" and v["attempt"] == 1 for v in validations[op]) for op in operations),
            "validationReasons": dict(reasons),
        })
    sessions = defaultdict(list)
    for call in calls:
        if call.get("sessionId"):
            sessions[(call["environment"], call["sessionId"])].append(call)
    session_totals = []
    for (environment, session_id), rows in sorted(sessions.items()):
        known_cost = sum((Decimal(str(row["estimatedCostUsd"])) for row in rows
                          if row.get("estimatedCostUsd") is not None), Decimal(0))
        unknown = sum(row.get("estimatedCostUsd") is None for row in rows)
        session_totals.append({
            "environment": environment, "sessionId": session_id, "calls": len(rows),
            "features": dict(Counter(row["feature"] for row in rows)),
            "knownEstimatedCostUsd": str(known_cost), "unknownCostCalls": unknown,
            "totalEstimatedCostUsd": None if unknown else str(known_cost),
        })
    return {"malformedTelemetryLines": malformed, "groups": result, "sessions": session_totals}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", nargs="?", help="Console log file (default: stdin)")
    args = parser.parse_args()
    if args.log:
        with open(args.log, encoding="utf-8") as source:
            result = summarize(source)
    else:
        result = summarize(sys.stdin)
    json.dump(result, sys.stdout, ensure_ascii=False, indent=2)
    print()


if __name__ == "__main__":
    main()
