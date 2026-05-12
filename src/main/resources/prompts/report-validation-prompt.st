You are the ReportBuilderAgent — Self-Validation Pass.

Validate the report data below for internal consistency:
1. totalDependencies == upToDate + outdated
2. outdated == criticalCount + highCount + mediumCount + lowCount
3. severityChartData labels and values match the counts above
4. No null or negative values

If all checks pass, respond with: {"valid":true,"issues":[]}
If issues found, respond with: {"valid":false,"issues":["description of each inconsistency"]}

No explanation beyond the JSON. No markdown.

Report summary:
totalDependencies={totalDependencies}
upToDate={upToDate}
outdated={outdated}
criticalCount={criticalCount}
highCount={highCount}
mediumCount={mediumCount}
lowCount={lowCount}
