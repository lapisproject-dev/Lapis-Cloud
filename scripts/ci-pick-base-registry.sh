#!/usr/bin/env bash
# V1.9.160: picks the registry the Dockerfile's base images are pulled from. The primary is the AWS public ECR mirror of Docker's official
# images; its anonymous pull limit stopped the image build twice (v0.37.0, v0.37.2, "toomanyrequests"). The base images are pinned by digest,
# and the same digest exists on the other mirrors, so a fallback changes where the bytes come from, never which bytes.
#
# Usage: ci-pick-base-registry.sh [Dockerfile]    prints the first registry from which EVERY `FROM ${BASE_REGISTRY}/...` image of the Dockerfile
# can be resolved (manifest only, no layers); exit 1 if none can. Registries to try (space separated, in order): $BASE_REGISTRIES.
# The result goes to the build as `--build-arg BASE_REGISTRY=...`; the Dockerfile default stays the primary, so a plain `docker build` still works.
set -uo pipefail
dockerfile="${1:-Dockerfile}"
registries="${BASE_REGISTRIES:-public.ecr.aws/docker/library mirror.gcr.io/library docker.io/library}"

mapfile -t images < <(sed -n 's/^FROM \${BASE_REGISTRY}\/\([^ ]*\).*/\1/p' "$dockerfile")
if [ "${#images[@]}" -eq 0 ]; then
  echo "no 'FROM \${BASE_REGISTRY}/...' line in $dockerfile" >&2
  exit 1
fi

for registry in $registries; do
  ok=1
  for image in "${images[@]}"; do
    if ! docker buildx imagetools inspect "$registry/$image" >/dev/null 2>&1; then
      echo "base image $image not resolvable from $registry" >&2
      ok=0
      break
    fi
  done
  if [ "$ok" = 1 ]; then
    echo "$registry"
    exit 0
  fi
done
echo "no registry can serve the base images of $dockerfile" >&2
exit 1
