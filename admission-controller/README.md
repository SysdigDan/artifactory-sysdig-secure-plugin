Sysdig Artifactory Plugin - Admission Controller Scanning Service
===============================

The Sysdig Artifactory Plugin can utilise the Sysdig Admission Controller Scanning Service for local scanning. 

Note: For Artifactory Cloud this would require external access to the Admission Controller Service.

Configuration
-----------------
1. Configure the Sysdig Admission Controller Scanning Service to be accessible by Artifactory
2. Optional: Sysdig Admission Controller Artifactory Credentials

Sysdig Admission Controller Scanning Service
-----------------
Configuration the Sysdig Admission Controller Scanning Service with the below example service for external accessibility.

```yaml
apiVersion: v1
kind: Service
metadata:
  labels:
    app.kubernetes.io/component: scanner
    app.kubernetes.io/instance: sysdig-admission-controller
    app.kubernetes.io/name: admission-controller
  name: sysdig-admission-controller-scanner-external
  namespace: sysdig-admission-controller
spec:
  ports:
  - name: https
    port: 8080
    protocol: TCP
    targetPort: http
  selector:
    app.kubernetes.io/component: scanner
    app.kubernetes.io/instance: sysdig-admission-controller
    app.kubernetes.io/name: admission-controller
  type: LoadBalancer
```

Optional: Sysdig Admission Controller Artifactory Credentials
-----------------
If your container registry is protected with credentials, you will need to give the Sysdig Admission Controller access

Create secret with credentials using .dockercfg
```
kubectl -n sysdig-admission-controller create secret generic sysdig-admission-controller-registry-auth --from-file=.dockercfg
```
.dockercfg is in the legacy format, example:
```
{
    "registry.artifactory.internal": {
        "auth": "ZmVy...........EFX"
    }
}
```
Make sure to update your admission controller deployment either with the helm values.yml
````
dockerCfgSecretName: "sysdig-admission-controller-registry-auth"
````

or directly in the deployment spec
```
    volumeMounts:
    ...
    - mountPath: /dockerauth
      name: dockercfg
      readOnly: true
    ...
```
```
  volumes:
  ...
  - name: dockercfg
    secret:
      defaultMode: 420
      secretName: sysdig-admission-controller-registry-auth
  ...
```

