import AVFoundation
import Vision
import Combine

@MainActor
final class ContinuityCameraSleepMonitor: NSObject, ObservableObject {
    @Published private(set) var status = "Cámara no conectada"
    @Published private(set) var probableSleep = false

    let session = AVCaptureSession()
    private let videoOutput = AVCaptureVideoDataOutput()
    private let queue = DispatchQueue(label: "javistv.tvos.sleep", qos: .userInitiated)
    private var activeInput: AVCaptureDeviceInput?
    private var candidateSince: Date?
    private var lastCenter: CGPoint?
    private var lastSignificantMovement = Date()
    private var lastFrameAt = Date.distantPast

    func connect(_ continuityDevice: AVContinuityDevice?) {
        guard let device = continuityDevice, let camera = device.videoDevices.first else {
            status = "No se ha seleccionado Continuity Camera"
            return
        }
        configure(camera)
    }

    func activatePreferredCameraIfAvailable() -> Bool {
        guard let camera = AVCaptureDevice.systemPreferredCamera else { return false }
        return configure(camera)
    }

    @discardableResult
    private func configure(_ camera: AVCaptureDevice) -> Bool {
        do {
            let input = try AVCaptureDeviceInput(device: camera)
            session.beginConfiguration()
            if let old = activeInput { session.removeInput(old) }
            guard session.canAddInput(input) else {
                session.commitConfiguration()
                status = "La cámara no puede añadirse a la sesión"
                return false
            }
            session.addInput(input)
            activeInput = input

            if session.outputs.contains(videoOutput) == false {
                videoOutput.alwaysDiscardsLateVideoFrames = true
                videoOutput.setSampleBufferDelegate(self, queue: queue)
                guard session.canAddOutput(videoOutput) else {
                    session.commitConfiguration()
                    status = "No se puede analizar vídeo"
                    return false
                }
                session.addOutput(videoOutput)
            }
            session.commitConfiguration()
            queue.async { [weak self] in self?.session.startRunning() }
            status = "Continuity Camera activa · detección local"
            return true
        } catch {
            status = "Error de cámara: \(error.localizedDescription)"
            return false
        }
    }

    private func evaluate(_ observation: VNHumanBodyPoseObservation) {
        guard
            let shoulder = try? observation.recognizedPoint(.neck),
            let root = try? observation.recognizedPoint(.root),
            shoulder.confidence > 0.45,
            root.confidence > 0.45
        else {
            resetCandidate()
            return
        }

        let center = CGPoint(x: (shoulder.location.x + root.location.x) / 2,
                             y: (shoulder.location.y + root.location.y) / 2)
        let dx = abs(root.location.x - shoulder.location.x)
        let dy = abs(root.location.y - shoulder.location.y)
        let lying = dx / max(dy, 0.01) > 0.75

        let now = Date()
        if let previous = lastCenter {
            let movement = hypot(center.x - previous.x, center.y - previous.y)
            if movement > 0.04 {
                lastSignificantMovement = now
                candidateSince = now
            }
        } else {
            candidateSince = now
            lastSignificantMovement = now
        }
        lastCenter = center

        guard lying else {
            probableSleep = false
            status = "Persona detectada · no tumbada"
            resetCandidate()
            return
        }

        let stable = now.timeIntervalSince(lastSignificantMovement)
        probableSleep = stable >= 180
        status = probableSleep
            ? "Probable sueño · dormitorio/sofá · sin acción de despertar"
            : "Persona tumbada · inmóvil \(Int(stable)) s"
    }

    private func resetCandidate() {
        candidateSince = nil
        lastCenter = nil
        lastSignificantMovement = Date()
    }
}

extension ContinuityCameraSleepMonitor: AVCaptureVideoDataOutputSampleBufferDelegate {
    nonisolated func captureOutput(_ output: AVCaptureOutput,
                                   didOutput sampleBuffer: CMSampleBuffer,
                                   from connection: AVCaptureConnection) {
        let now = Date()
        Task { @MainActor [weak self] in
            guard let self, now.timeIntervalSince(self.lastFrameAt) >= 1.0 else { return }
            self.lastFrameAt = now
            guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
            let request = VNDetectHumanBodyPoseRequest()
            do {
                let handler = VNImageRequestHandler(cvPixelBuffer: pixelBuffer, orientation: .up)
                try handler.perform([request])
                if let body = request.results?.first {
                    self.evaluate(body)
                } else {
                    self.probableSleep = false
                    self.status = "Sin persona visible"
                    self.resetCandidate()
                }
            } catch {
                self.status = "Vision: \(error.localizedDescription)"
            }
        }
    }
}
