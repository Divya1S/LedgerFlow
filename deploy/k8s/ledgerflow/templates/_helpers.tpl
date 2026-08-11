{{- define "ledgerflow.fullname" -}}
{{ .Release.Name }}
{{- end }}

{{- define "ledgerflow.labels" -}}
app.kubernetes.io/name: ledgerflow
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "ledgerflow.selectorLabels" -}}
app.kubernetes.io/name: ledgerflow
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}
