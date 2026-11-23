using System.Collections;
using System.ComponentModel;
using System.Globalization;
using System.Reflection;
using System.Text.Json;
using System.Text.Json.Serialization.Metadata;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Infrastructure;
using Microsoft.AspNetCore.Mvc.ModelBinding;
using Microsoft.AspNetCore.Mvc.Routing;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using Nitrate.Companion.Hosting;

namespace Nitrate.Companion.Logic;

/// <summary>
/// Runs the logic's ASP.NET controllers (unchanged: <c>[Route]</c>, <c>[HttpGet]</c>, <c>[FromQuery]</c>,
/// <c>[FromBody]</c>, <c>[Authorize]</c>, <c>[Produces]</c>) behind the shell's one catch-all
/// controller, doing what MVC did for them: route matching with MVC's precedence, binding with its
/// conversions and implicit "required" rules, the same validation answers (the server's own
/// <see cref="ApiBehaviorOptions.InvalidModelStateResponseFactory"/>), 415 for a body that isn't
/// JSON, ProblemDetails for client errors and <c>[Produces]</c> content types. The logic's own types
/// are turned into JSON here, with a copy of the server's MVC JSON settings, so no logic type is ever
/// cached by the server (which would keep an old logic from unloading); MVC then writes that JSON,
/// negotiating as it always did.
/// </summary>
public sealed class ApiRouter
{
    private readonly List<Endpoint> _endpoints = new();
    private readonly Assembly _logicAssembly;
    private readonly Dictionary<Type, ObjectFactory> _factories = new();
    private JsonSerializerOptions? _json;

    /// <summary>Initializes a new instance of the <see cref="ApiRouter"/> class.</summary>
    /// <param name="controllers">The controller types to serve.</param>
    /// <param name="logicAssembly">The assembly whose types are the logic's own (serialized here).</param>
    public ApiRouter(IEnumerable<Type> controllers, Assembly logicAssembly)
    {
        _logicAssembly = logicAssembly;
        foreach (var type in controllers)
        {
            _factories[type] = ActivatorUtilities.CreateFactory(type, Type.EmptyTypes);
            var classRoutes = type.GetCustomAttributes<RouteAttribute>(true).Select(r => r.Template).DefaultIfEmpty(string.Empty).ToList();
            var classAuth = type.GetCustomAttributes(true).OfType<IAuthorizeData>().ToList();
            var classAnonymous = type.GetCustomAttributes(true).OfType<IAllowAnonymous>().Any();
            var classProduces = type.GetCustomAttribute<ProducesAttribute>(true);
            foreach (var method in type.GetMethods(BindingFlags.Instance | BindingFlags.Public))
            {
                var verbs = method.GetCustomAttributes<HttpMethodAttribute>(true).ToList();
                if (verbs.Count == 0 || method.IsDefined(typeof(NonActionAttribute)))
                {
                    continue;
                }

                var auth = classAuth.Concat(method.GetCustomAttributes(true).OfType<IAuthorizeData>()).ToList();
                var anonymous = classAnonymous || method.GetCustomAttributes(true).OfType<IAllowAnonymous>().Any();
                var produces = method.GetCustomAttribute<ProducesAttribute>(true) ?? classProduces;
                foreach (var verb in verbs)
                {
                    foreach (var classRoute in classRoutes)
                    {
                        var template = Combine(classRoute, verb.Template);
                        _endpoints.Add(new Endpoint(
                            type,
                            method,
                            verb.HttpMethods.ToHashSet(StringComparer.OrdinalIgnoreCase),
                            Segment.Parse(template),
                            template,
                            anonymous,
                            auth.Select(a => a.Policy).ToList(),
                            produces?.ContentTypes.ToList()));
                    }
                }
            }
        }
    }

    /// <summary>Gets every route served, as "METHOD template", for tests and logs.</summary>
    public IEnumerable<string> Routes => _endpoints.SelectMany(e => e.Methods.Select(m => $"{m} {e.Template}"));

    /// <summary>Which endpoint a request is for.</summary>
    public RouteMatch Match(string method, string path)
    {
        var parts = path.Split('/', StringSplitOptions.RemoveEmptyEntries);
        Endpoint? best = null;
        Dictionary<string, string>? bestValues = null;
        var pathMatched = false;
        foreach (var endpoint in _endpoints)
        {
            if (endpoint.TryMatch(parts) is not { } values)
            {
                continue;
            }

            pathMatched = true;
            if (!endpoint.Methods.Contains(method))
            {
                continue;
            }

            if (best is null || Precedence(endpoint, best) < 0)
            {
                best = endpoint;
                bestValues = values;
            }
        }

        if (best is null)
        {
            return pathMatched ? RouteMatch.MethodNotAllowed : RouteMatch.NotFound;
        }

        return new RouteMatch(RouteMatchKind.Found, best.Anonymous || best.Policies.Count == 0, best.Policies, new Matched(best, bestValues!));
    }

    /// <summary>Runs a matched endpoint's action with the request's services and the given controller services.</summary>
    public async Task<IActionResult> InvokeAsync(RouteMatch match, ControllerContext shellContext, IServiceProvider services)
    {
        if (match.Handle is not Matched matched)
        {
            throw new ArgumentException("Not a route of this router.", nameof(match));
        }

        var endpoint = matched.Endpoint;
        var http = shellContext.HttpContext;
        var routeData = new RouteData(shellContext.RouteData);
        foreach (var (key, value) in matched.Values)
        {
            routeData.Values[key] = value;
        }

        var context = new ControllerContext(new ActionContext(http, routeData, shellContext.ActionDescriptor, new ModelStateDictionary()));
        var json = Json(http);
        var (arguments, refused) = await BindAsync(endpoint, matched.Values, context, json).ConfigureAwait(false);
        IActionResult result;
        if (refused is not null)
        {
            result = refused;
        }
        else
        {
            var controller = (ControllerBase)_factories[endpoint.Controller](services, []);
            controller.ControllerContext = context;
            try
            {
                result = await ResultOf(endpoint.Method.Invoke(controller, arguments), endpoint.Method.ReturnType).ConfigureAwait(false);
            }
            catch (TargetInvocationException e) when (e.InnerException is not null)
            {
                System.Runtime.ExceptionServices.ExceptionDispatchInfo.Capture(e.InnerException).Throw();
                throw;
            }
            finally
            {
                (controller as IDisposable)?.Dispose();
            }
        }

        return Finish(result, endpoint, context, json);
    }

    private static string Combine(string classRoute, string? methodRoute)
    {
        if (string.IsNullOrEmpty(methodRoute))
        {
            return classRoute.Trim('/');
        }

        if (methodRoute.StartsWith('/') || methodRoute.StartsWith("~/", StringComparison.Ordinal))
        {
            return methodRoute.TrimStart('~').Trim('/');
        }

        return string.IsNullOrEmpty(classRoute) ? methodRoute.Trim('/') : classRoute.Trim('/') + "/" + methodRoute.Trim('/');
    }

    // MVC's order: segment by segment, a literal beats a parameter, which beats a catch-all.
    private static int Precedence(Endpoint a, Endpoint b)
    {
        for (var i = 0; i < Math.Min(a.Segments.Count, b.Segments.Count); i++)
        {
            var c = a.Segments[i].Rank.CompareTo(b.Segments[i].Rank);
            if (c != 0)
            {
                return c;
            }
        }

        return b.Segments.Count.CompareTo(a.Segments.Count);
    }

    private JsonSerializerOptions Json(HttpContext http)
    {
        if (_json is { } json)
        {
            return json;
        }

        // A copy of the server's own MVC JSON settings (PascalCase, its converters), with its own cache.
        var server = http.RequestServices.GetService<IOptions<JsonOptions>>()?.Value.JsonSerializerOptions;
        json = server is null ? new JsonSerializerOptions(JsonSerializerDefaults.Web) : new JsonSerializerOptions(server);
        json.TypeInfoResolver = new DefaultJsonTypeInfoResolver();
        json.MakeReadOnly();
        return _json = json;
    }

    private async Task<(object?[] Arguments, IActionResult? Refused)> BindAsync(Endpoint endpoint, IReadOnlyDictionary<string, string> routeValues, ControllerContext context, JsonSerializerOptions json)
    {
        var http = context.HttpContext;
        var parameters = endpoint.Method.GetParameters();
        var arguments = new object?[parameters.Length];
        var nullability = new NullabilityInfoContext();
        for (var i = 0; i < parameters.Length; i++)
        {
            var p = parameters[i];
            var type = p.ParameterType;
            if (type == typeof(CancellationToken))
            {
                arguments[i] = http.RequestAborted;
                continue;
            }

            var fromBody = p.GetCustomAttribute<FromBodyAttribute>();
            var fromQuery = p.GetCustomAttribute<FromQueryAttribute>();
            var fromRoute = p.GetCustomAttribute<FromRouteAttribute>();
            var name = fromQuery?.Name ?? fromRoute?.Name ?? p.Name!;
            var nullable = Nullable.GetUnderlyingType(type) is not null || (!type.IsValueType && nullability.Create(p).WriteState != NullabilityState.NotNull);
            if (fromBody is not null || (fromQuery is null && fromRoute is null && !IsSimple(type)))
            {
                var allowEmpty = nullable || p.HasDefaultValue;
                var (value, refused) = await ReadBodyAsync(p, allowEmpty, context, json).ConfigureAwait(false);
                if (refused is not null)
                {
                    return (arguments, refused);
                }

                arguments[i] = value;
                continue;
            }

            string? text = null;
            var present = false;
            if (fromRoute is not null || (fromQuery is null && routeValues.ContainsKey(name)))
            {
                present = routeValues.TryGetValue(name, out text);
            }
            else if (http.Request.Query.TryGetValue(name, out var values) && values.Count > 0)
            {
                present = true;
                text = values[0];
            }

            if (!present)
            {
                arguments[i] = p.HasDefaultValue ? p.DefaultValue : Default(type);
                if (arguments[i] is null && !nullable && !type.IsValueType && !p.HasDefaultValue)
                {
                    context.ModelState.AddModelError(name, $"The {name} field is required.");
                }

                continue;
            }

            if (type == typeof(string))
            {
                // MVC turns an empty value into null.
                arguments[i] = string.IsNullOrEmpty(text) ? null : text;
                if (arguments[i] is null && !nullable)
                {
                    context.ModelState.AddModelError(name, $"The {name} field is required.");
                }

                continue;
            }

            var target = Nullable.GetUnderlyingType(type) ?? type;
            if (string.IsNullOrWhiteSpace(text))
            {
                arguments[i] = Default(type);
                if (Nullable.GetUnderlyingType(type) is null && type.IsValueType)
                {
                    context.ModelState.AddModelError(name, $"The value '{text}' is invalid.");
                }

                continue;
            }

            try
            {
                arguments[i] = TypeDescriptor.GetConverter(target).ConvertFromString(null, CultureInfo.InvariantCulture, text);
            }
            catch (Exception e) when (e is FormatException or NotSupportedException or ArgumentException or OverflowException || e.InnerException is FormatException or OverflowException)
            {
                arguments[i] = Default(type);
                context.ModelState.AddModelError(name, $"The value '{text}' is not valid.");
            }
        }

        if (!context.ModelState.IsValid)
        {
            return (arguments, Invalid(context));
        }

        return (arguments, null);
    }

    private async Task<(object? Value, IActionResult? Refused)> ReadBodyAsync(ParameterInfo p, bool allowEmpty, ControllerContext context, JsonSerializerOptions json)
    {
        var request = context.HttpContext.Request;
        var canHaveBody = context.HttpContext.Features.Get<Microsoft.AspNetCore.Http.Features.IHttpRequestBodyDetectionFeature>()?.CanHaveBody
            ?? request.ContentLength is not 0;
        if (!IsJson(request.ContentType))
        {
            if (allowEmpty && (!canHaveBody || request.ContentLength == 0))
            {
                return (null, null);
            }

            return (null, new UnsupportedMediaTypeResult());
        }

        object? value = null;
        var empty = true;
        try
        {
            if (canHaveBody)
            {
                using var body = new MemoryStream();
                await request.Body.CopyToAsync(body, context.HttpContext.RequestAborted).ConfigureAwait(false);
                if (body.Length > 0)
                {
                    empty = false;
                    value = JsonSerializer.Deserialize(body.GetBuffer().AsSpan(0, (int)body.Length), p.ParameterType, json);
                }
            }
        }
        catch (JsonException e)
        {
            context.ModelState.AddModelError(e.Path ?? "$", e.Message);
            Required();
            return (null, Invalid(context));
        }

        if (value is null)
        {
            if (allowEmpty)
            {
                return (null, null);
            }

            if (empty)
            {
                context.ModelState.AddModelError(string.Empty, "A non-empty request body is required.");
            }

            Required();
            return (null, Invalid(context));
        }

        Validate(value, string.Empty, context.ModelState, new NullabilityInfoContext(), 0);
        return context.ModelState.IsValid ? (value, null) : (null, Invalid(context));

        // MVC's implicit [Required] on a non-nullable body parameter.
        void Required()
        {
            if (!allowEmpty)
            {
                context.ModelState.AddModelError(p.Name!, $"The {p.Name} field is required.");
            }
        }
    }

    // MVC's implicit [Required] on non-nullable reference properties, down through objects and lists.
    private void Validate(object value, string prefix, ModelStateDictionary state, NullabilityInfoContext nullability, int depth)
    {
        if (depth > 32)
        {
            return;
        }

        var type = value.GetType();
        if (value is IEnumerable list and not string)
        {
            if (value is IDictionary)
            {
                return;
            }

            var index = 0;
            foreach (var item in list)
            {
                if (item is not null && IsLogicType(item.GetType()))
                {
                    Validate(item, $"{prefix}[{index}]", state, nullability, depth + 1);
                }

                index++;
            }

            return;
        }

        if (!IsLogicType(type))
        {
            return;
        }

        foreach (var property in type.GetProperties(BindingFlags.Instance | BindingFlags.Public))
        {
            if (property.GetIndexParameters().Length > 0 || !property.CanRead || property.GetMethod is null)
            {
                continue;
            }

            var key = prefix.Length == 0 ? property.Name : prefix + "." + property.Name;
            var item = property.GetValue(value);
            if (item is null)
            {
                if (!property.PropertyType.IsValueType && nullability.Create(property).ReadState == NullabilityState.NotNull)
                {
                    state.AddModelError(key, $"The {property.Name} field is required.");
                }

                continue;
            }

            if (item is IEnumerable and not string || IsLogicType(item.GetType()))
            {
                Validate(item, key, state, nullability, depth + 1);
            }
        }
    }

    private static IActionResult Invalid(ControllerContext context)
    {
        var options = context.HttpContext.RequestServices.GetService<IOptions<ApiBehaviorOptions>>()?.Value;
        return options?.InvalidModelStateResponseFactory is { } factory
            ? factory(context)
            : new BadRequestObjectResult(new ValidationProblemDetails(context.ModelState));
    }

    private IActionResult Finish(IActionResult result, Endpoint endpoint, ControllerContext context, JsonSerializerOptions json)
    {
        if (endpoint.Produces is { } produces)
        {
            // What [ApiController]'s client error filter and then [Produces] did, in that order.
            var behavior = context.HttpContext.RequestServices.GetService<IOptions<ApiBehaviorOptions>>()?.Value;
            if (result is IClientErrorActionResult { StatusCode: >= 400 } clientError && behavior?.SuppressMapClientErrors != true
                && context.HttpContext.RequestServices.GetService<IClientErrorFactory>()?.GetClientError(context, clientError) is { } mapped)
            {
                result = mapped;
            }

            if (result is ObjectResult withTypes)
            {
                withTypes.ContentTypes.Clear();
                foreach (var type in produces)
                {
                    withTypes.ContentTypes.Add(type);
                }
            }
        }

        if (result is ObjectResult { Value: { } value } objectResult && IsLogicType(value.GetType()))
        {
            objectResult.Value = JsonSerializer.SerializeToElement(value, value.GetType(), json);
            objectResult.DeclaredType = typeof(JsonElement);
        }

        return result;
    }

    private static async Task<IActionResult> ResultOf(object? returned, Type returnType)
    {
        if (returned is Task task)
        {
            await task.ConfigureAwait(false);
            if (!returnType.IsGenericType)
            {
                return new EmptyResult();
            }

            returnType = returnType.GetGenericArguments()[0];
            returned = task.GetType().GetProperty(nameof(Task<object>.Result))!.GetValue(task);
        }
        else if (returnType == typeof(void))
        {
            return new EmptyResult();
        }

        return returned switch
        {
            IActionResult action => action,
            IConvertToActionResult convert => convert.Convert(),
            _ => new ObjectResult(returned) { DeclaredType = returnType },
        };
    }

    /// <summary>Whether a value of this type is the logic's own (or holds the logic's own), so it's serialized here.</summary>
    public bool IsLogicType(Type type)
    {
        if (type.Assembly == _logicAssembly)
        {
            return true;
        }

        if (type.HasElementType && type.GetElementType() is { } element)
        {
            return IsLogicType(element);
        }

        return type.IsGenericType && type.GetGenericArguments().Any(IsLogicType);
    }

    private static bool IsJson(string? contentType)
    {
        if (string.IsNullOrEmpty(contentType) || !Microsoft.Net.Http.Headers.MediaTypeHeaderValue.TryParse(contentType, out var media))
        {
            return false;
        }

        var value = media.MediaType.Value ?? string.Empty;
        return value.Equals("application/json", StringComparison.OrdinalIgnoreCase)
            || value.Equals("text/json", StringComparison.OrdinalIgnoreCase)
            || (value.StartsWith("application/", StringComparison.OrdinalIgnoreCase) && value.EndsWith("+json", StringComparison.OrdinalIgnoreCase));
    }

    private static bool IsSimple(Type type)
    {
        var t = Nullable.GetUnderlyingType(type) ?? type;
        return t.IsPrimitive || t.IsEnum || t == typeof(string) || t == typeof(Guid) || t == typeof(decimal) || t == typeof(DateTime)
            || t == typeof(DateTimeOffset) || t == typeof(TimeSpan) || TypeDescriptor.GetConverter(t).CanConvertFrom(typeof(string));
    }

    private static object? Default(Type type) => type.IsValueType && Nullable.GetUnderlyingType(type) is null ? Activator.CreateInstance(type) : null;

    private sealed record Matched(Endpoint Endpoint, Dictionary<string, string> Values);

    private sealed record Segment(string? Literal, string? Parameter, bool CatchAll, bool Optional)
    {
        // Lower is more specific.
        public int Rank => Literal is not null ? 0 : CatchAll ? 2 : 1;

        public static IReadOnlyList<Segment> Parse(string template) =>
            template.Split('/', StringSplitOptions.RemoveEmptyEntries).Select(s =>
            {
                if (!s.StartsWith('{') || !s.EndsWith('}'))
                {
                    return new Segment(s, null, false, false);
                }

                var inner = s[1..^1];
                var catchAll = inner.StartsWith('*');
                inner = inner.TrimStart('*');
                var optional = inner.EndsWith('?');
                inner = inner.TrimEnd('?');
                var colon = inner.IndexOf(':', StringComparison.Ordinal);
                var eq = inner.IndexOf('=', StringComparison.Ordinal);
                var cut = new[] { colon, eq }.Where(x => x >= 0).DefaultIfEmpty(inner.Length).Min();
                return new Segment(null, inner[..cut], catchAll, optional);
            }).ToList();
    }

    private sealed record Endpoint(
        Type Controller,
        MethodInfo Method,
        HashSet<string> Methods,
        IReadOnlyList<Segment> Segments,
        string Template,
        bool Anonymous,
        IReadOnlyList<string?> Policies,
        IReadOnlyList<string>? Produces)
    {
        public Dictionary<string, string>? TryMatch(string[] parts)
        {
            var values = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
            for (var i = 0; i < Segments.Count; i++)
            {
                var segment = Segments[i];
                if (segment.CatchAll)
                {
                    values[segment.Parameter!] = string.Join('/', parts.Skip(i));
                    return values;
                }

                if (i >= parts.Length)
                {
                    return segment.Optional ? values : null;
                }

                if (segment.Literal is { } literal)
                {
                    if (!literal.Equals(parts[i], StringComparison.OrdinalIgnoreCase))
                    {
                        return null;
                    }
                }
                else
                {
                    values[segment.Parameter!] = parts[i];
                }
            }

            return parts.Length == Segments.Count ? values : null;
        }
    }
}
