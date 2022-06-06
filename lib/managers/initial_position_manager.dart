import 'package:flutter/services.dart';
import 'package:vector_math/vector_math_64.dart';

/// Handles all anchor-related functionality of an [ARView], including configuration and usage of collaborative sessions
class InitialPositionHandler {
  /// Platform channel used for communication from and to [ARAnchorManager]
  late MethodChannel _channel;

  InitialPositionHandler() {
    _channel = MethodChannel('initialPosition');
  }

  /// Remove given anchor and all its children from the AR Scene

  Future<dynamic> position() async {
    print("Getting Position from Platform");
    var posX = await _channel.invokeMethod<double?>('position-x');
    print("Got position List");
    if (posX == null) return null;
    print("Position is not null");

    var posY = await _channel.invokeMethod<double?>('position-y');
    var posZ = await _channel.invokeMethod<double?>('position-z');

    Vector3 positionVector = Vector3(posX, posY!, posZ!);
    return positionVector;
  }

  Future<dynamic> rotation() async {
    print("Getting Position from Platform");
    var rotX = await _channel.invokeMethod<double?>('rotation-x');
    print("Got position List");
    if (rotX == null) return null;

    var rotY = await _channel.invokeMethod<double?>('rotation-y');
    var rotZ = await _channel.invokeMethod<double?>('rotation-z');

    List<double> rotVector = [rotX, rotY!, rotZ!];
    return rotVector;
  }
}
