{{/* Shared label / name helpers */}}
{{- define "vc.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "vc.fullname" -}}
{{- if .Values.fullnameOverride -}}{{ .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}{{ printf "%s-%s" .Release.Name (include "vc.name" .) | trunc 63 | trimSuffix "-" }}{{- end -}}
{{- end -}}

{{- define "vc.labels" -}}
app.kubernetes.io/name: {{ include "vc.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end -}}

{{- define "vc.selectorLabels" -}}
app.kubernetes.io/name: {{ include "vc.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}
