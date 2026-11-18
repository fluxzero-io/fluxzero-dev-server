export const githubWorkflowSnippet = `# Keep your existing build and immutable image publication steps.
# .github/workflows/deploy.yml — deploy job / steps
permissions:
  contents: read
  id-token: write
steps:
  - uses: fluxzero-io/fluxzero-jwt-action@v2
    id: fluxzero
    with:
      mode: oidc
  - uses: fluxzero-io/fluxzero-deploy-action@v1
    with:
      token: \${{ steps.fluxzero.outputs.deploy-token }}
      cluster-name: \${{ vars.FLUXZERO_CLUSTER_NAME }}
      application-name: \${{ vars.FLUXZERO_APPLICATION_NAME }}
      image-name: \${{ vars.FLUXZERO_IMAGE_NAME }}
      version: \${{ github.sha }}`;
