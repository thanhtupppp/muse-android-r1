package vn.tutu.musemobile.domain

sealed class ProvisioningState {
    data object Idle : ProvisioningState()
    data object CheckingR1 : ProvisioningState()
    data object SelectingWifi : ProvisioningState()
    data object Provisioning : ProvisioningState()
    data object WaitingForR1 : ProvisioningState()
    data class Success(val ip: String) : ProvisioningState()
    data class Failure(val message: String) : ProvisioningState()
}
