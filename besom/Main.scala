import besom.*
import besom.api.kubernetes.apps.v1.{Deployment, DeploymentArgs}
import besom.api.kubernetes.core.v1.inputs.{
  ContainerArgs,
  PodSpecArgs,
  PodTemplateSpecArgs
}
import besom.api.kubernetes.meta.v1.inputs.{LabelSelectorArgs, ObjectMetaArgs}
import besom.api.kubernetes.apps.v1.inputs.DeploymentSpecArgs
import besom.api.docker
import besom.api.docker.ImageArgs
import besom.api.docker.inputs.DockerBuildArgs
import besom.api.kubernetes.core.v1.Service
import besom.api.kubernetes.core.v1.ServiceArgs
import besom.api.kubernetes.core.v1.inputs.ServiceSpecArgs
import besom.api.kubernetes.core.v1.inputs.ServicePortArgs
import besom.api.kubernetes.networking.v1.Ingress
import besom.api.kubernetes.networking.v1.IngressArgs
import besom.api.kubernetes.networking.v1.inputs.IngressSpecArgs
import besom.api.kubernetes.networking.v1.inputs.IngressRuleArgs
import besom.api.kubernetes.networking.v1.inputs.HttpIngressRuleValueArgs
import besom.api.kubernetes.networking.v1.inputs.HttpIngressPathArgs
import besom.api.kubernetes.networking.v1.inputs.IngressBackendArgs
import besom.api.kubernetesingressnginx.IngressController
import besom.api.kubernetesingressnginx.IngressControllerArgs
import besom.api.kubernetesingressnginx.inputs.ControllerArgs
import besom.api.kubernetesingressnginx.inputs.ControllerPublishServiceArgs
import besom.api.kubernetes.networking.v1.inputs.IngressServiceBackendArgs
import besom.api.kubernetes.networking.v1.inputs.ServiceBackendPortArgs

def appService(path: Output[String], name: NonEmptyString, servicePort: Int = 9999, targetPort: Int = 80)(using
    besom.types.Context
) =
  val image = docker.Image(
    name,
    ImageArgs(
      imageName = s"docker.io/keynmol/$name:latest",
      build = DockerBuildArgs(context = path),
      skipPush = true
    )
  )

  val appLabels = Map("app" -> name)
  val deployment = Deployment(
    name,
    DeploymentArgs(
      spec = DeploymentSpecArgs(
        selector = LabelSelectorArgs(matchLabels = appLabels),
        replicas = 1,
        template = PodTemplateSpecArgs(
          metadata = ObjectMetaArgs(
            labels = appLabels
          ),
          spec = PodSpecArgs(
            containers = List(
              ContainerArgs(
                name = name,
                image = image.imageName
              )
            )
          )
        )
      )
    )
  )

  Service(
    s"$name-service",
    ServiceArgs(
      metadata = ObjectMetaArgs(
        labels = deployment.spec.template.metadata.labels
      ),
      spec = ServiceSpecArgs(
        `type` = "LoadBalancer",
        ports =
          List(ServicePortArgs(port = servicePort, targetPort = targetPort, protocol = "TCP")),
        selector = appLabels
      )
    )
  )
end appService

@main def main = Pulumi.run {
  val mimalyzer = appService(path = p"../apps/mimalyzer", "mimalyzer")

  val controller = IngressController(
    "controller",
    IngressControllerArgs(controller =
      ControllerArgs(publishService =
        ControllerPublishServiceArgs(enabled = true)
      )
    )
  )

  val ingress = Ingress(
    "ingress",
    IngressArgs(
      metadata = ObjectMetaArgs(annotations =
        Map(
          "kubernetes.io/ingress.class" -> "nginx",
          "nginx.ingress.kubernetes.io/ssl-redirect" -> "true",
          "nginx.ingress.kubernetes.io/proxy-body-size" -> "10m",
          // https://github.com/pulumi/pulumi-kubernetes/issues/1810#issuecomment-978387032
          "pulumi.com/skipAwait" -> "true"
        )
      ),
      spec = IngressSpecArgs(rules =
        List(
          IngressRuleArgs(
            host = "mimalyzer.indoorvivants.com",
            http = HttpIngressRuleValueArgs(
              List(
                HttpIngressPathArgs(
                  backend = IngressBackendArgs(
                    service = IngressServiceBackendArgs(
                      name = mimalyzer.metadata.name.getOrFail(???),
                      port = ServiceBackendPortArgs(number = 9999)
                    )
                  ),
                  path = "/",
                  pathType = "Prefix"
                )
              )
            )
          )
        )
      )
    )
  )

  Stack.exports(
    i = ingress,
    c = controller.status.name
  )
}
